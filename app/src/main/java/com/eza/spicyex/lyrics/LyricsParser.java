package com.eza.spicyex.lyrics;

import android.content.Context;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.eza.spicyex.SpotifyTrack;
import com.eza.spicyex.lyrics.reading.ReadingModels.CanonicalLine;
import com.eza.spicyex.lyrics.reading.SyllableCanonicalizer;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.eza.spicyex.xposed.XpLog;
import static com.eza.spicyex.lyrics.LyricUtils.cleanInvisibles;
import static com.eza.spicyex.lyrics.LyricUtils.cleanInvisiblesPreserveEdges;
import static com.eza.spicyex.lyrics.LyricUtils.isBlank;
import static com.eza.spicyex.lyrics.LyricUtils.safe;
import static com.eza.spicyex.lyrics.LyricUtils.trackIdFromUri;

/** Source adapters for remote (Apple Music, Spicy-shaped JSON) and LRCLIB lyric payloads. */
public final class LyricsParser implements LyricsRepository.Parser {
    private static final String TAG = "[SpotifyPlusSpicyParser]";
    private static final Pattern LRC_TIMESTAMP = Pattern.compile("^\\[(\\d{1,3}):(\\d{2})(?:\\.(\\d{1,3}))?\\]\\s*(.*)$");

    private final Finalizer finalizer;

    public LyricsParser(Finalizer finalizer) {
        this.finalizer = finalizer;
    }

    @Override
    public LyricsDocument parseSpicyLyrics(Context context, SpotifyTrack track, String raw, boolean fromCache) {
        log(TAG + " parseSpicyLyrics track=" + (track == null ? "null" : safe(track.uri))
                + " fromCache=" + fromCache + " bytes=" + (raw == null ? 0 : raw.length()));
        SpicyResponseMetadata metadata = new SpicyResponseMetadata();
        JsonElement envelope = JsonParser.parseString(raw);
        boolean noticePresent = SpicyQueryEnvelope.noticePresent(envelope);
        JsonElement selected = envelope;
        if (envelope.isJsonObject() && envelope.getAsJsonObject().has("queries")) {
            selected = SpicyQueryEnvelope.result(envelope);
            if (selected == null) throw new IllegalStateException("Apple Music operation 0 missing");
        }
        JsonElement root = unpackSpicyPayloads(selected, metadata, false);
        JsonObject data = findLyricsData(root);
        if (data == null) throw new IllegalStateException("lyrics data not found");

        String type = Json.optString(data, "Type", "type");
        String language = Json.optString(data, "Language", "language");
        LyricsDocument doc = new LyricsDocument();
        doc.trackId = trackIdFromUri(track == null ? "" : track.uri);
        doc.durationMs = track == null ? 0 : Math.max(0, track.duration);
        doc.startTimeMs = secondsToMs(Json.optDouble(data, 0d, "StartTime", "startTime"));
        doc.type = type == null ? "Unknown" : type;
        doc.language = language == null ? "" : language;
        doc.fetchSource = fromCache ? "apple_music_cache" : "apple_music";
        doc.spicyPackedPayload = metadata.packedPayload;
        doc.spicyEnvelopeNoticePresent = noticePresent;
        doc.spicyQueryStatus = metadata.queryStatus != null ? metadata.queryStatus : 200;
        doc.spicyFormat = metadata.format == null ? "" : metadata.format;
        doc.spicyPoisoned = false;
        doc.spicyQualityReason = null;
        doc.provider = providerLabelFromSource(firstNonBlank(
                Json.optString(data, "source", "Source", "Provider", "provider"),
                Json.findFirstString(root, "source", "Source", "Provider", "provider")
        ), type);
        doc.songWriters = joinSongWriters(Json.optArray(data, "SongWriters", "songWriters", "Writers"));
        JsonArray selectedLines = Json.optArray(data, "Lines", "lines", "Content", "content");
        log(TAG + " spicy parse selected type=" + type + " candidateLines=" + (selectedLines == null ? 0 : selectedLines.size()));

        if ("Static".equalsIgnoreCase(type)) {
            parseStatic(data, doc);
        } else if ("Line".equalsIgnoreCase(type)) {
            parseLine(data, doc);
        } else if ("Syllable".equalsIgnoreCase(type)) {
            parseSyllable(data, doc);
        } else {
            throw new IllegalStateException("unsupported lyrics type " + type);
        }

        finalizeParsedDocument(context, doc);
        return doc;
    }

    @Override
    public LyricsDocument parseLrclibLyrics(Context context, SpotifyTrack track, String body) {
        JsonElement root = JsonParser.parseString(body);
        List<JsonObject> candidates = new ArrayList<>();
        if (root.isJsonArray()) {
            for (JsonElement element : root.getAsJsonArray()) {
                if (element.isJsonObject()) candidates.add(element.getAsJsonObject());
            }
        } else if (root.isJsonObject()) {
            candidates.add(root.getAsJsonObject());
        }
        return parseLrclibCandidates(context, track, candidates);
    }

    /**
     * Builds a document from merged LRCLIB candidates gathered across query variants
     * (raw title, normalized title, free-text fallback). Raw-variant results must come first;
     * residual ties keep their order so exact remaster records win.
     */
    LyricsDocument parseLrclibCandidates(Context context, SpotifyTrack track, List<JsonObject> candidates) {
        double trackDurationSec = track == null || track.duration <= 0 ? -1d : track.duration / 1000d;
        int best = LrclibQueryPlanner.pickBest(candidates, trackDurationSec);
        if (best < 0) throw new IllegalStateException("no LRCLIB result");
        JsonObject chosen = candidates.get(best);

        LyricsDocument doc = new LyricsDocument();
        doc.trackId = trackIdFromUri(track == null ? "" : track.uri);
        doc.durationMs = track == null ? 0 : Math.max(0, track.duration);
        doc.fetchSource = "lrclib";
        doc.provider = "LRCLIB";
        doc.language = "";

        String synced = Json.optString(chosen, "syncedLyrics");
        if (!isBlank(synced)) {
            doc.type = "Line";
            parseLrcLines(synced, doc);
        } else {
            doc.type = "Static";
            parsePlainLines(Json.optString(chosen, "plainLyrics"), doc);
        }
        finalizeParsedDocument(context, doc);
        return doc;
    }

    /**
     * AMLL TTML DB adapter: word-timed community TTML becomes a {@code Word} document through
     * the same finalizer as every other source, so downstream lanes see identical shape.
     */
    public LyricsDocument parseAmllTtml(Context context, SpotifyTrack track, String ttml) {
        try {
            LyricsDocument doc = AmllTtmlParser.parse(ttml,
                    trackIdFromUri(track == null ? "" : track.uri),
                    track == null ? 0 : Math.max(0, track.duration));
            finalizeParsedDocument(context, doc);
            return doc;
        } catch (RuntimeException rethrown) {
            throw rethrown;
        } catch (Exception malformed) {
            throw new IllegalStateException("AMLL TTML parse failed: " + malformed.getMessage(),
                    malformed);
        }
    }

    private void parseLrcLines(String synced, LyricsDocument doc) {
        String[] rawLines = synced.split("\\r?\\n");
        for (String rawLine : rawLines) {
            Matcher matcher = LRC_TIMESTAMP.matcher(cleanInvisibles(rawLine));
            if (!matcher.matches()) continue;
            long minutes = parseLongSafe(matcher.group(1));
            long seconds = parseLongSafe(matcher.group(2));
            String fraction = matcher.group(3);
            long millis = 0;
            if (fraction != null && !fraction.isEmpty()) {
                String ms = (fraction + "000").substring(0, 3);
                millis = parseLongSafe(ms);
            }
            String text = cleanInvisibles(matcher.group(4));
            LyricsLine line = new LyricsLine();
            line.startMs = minutes * 60000 + seconds * 1000 + millis;
            if (doc.startTimeMs <= 0 && line.startMs > 0) doc.startTimeMs = line.startMs;
            line.text = isBlank(text) ? "♪" : text;
            line.interlude = isBlank(text);
            applySecondaryText(line, new JsonObject());
            doc.lines.add(line);
        }
        collapseShortLrcGaps(doc);
    }

    /**
     * LRC files mark the end of a sung line with an empty timestamp ("[00:20.779]"). Every one of
     * those became an interlude row, so a 1.5s breath between two lines showed the interlude dots.
     * An empty line now ends the line before it, and stays an interlude only when the silence up
     * to the next line is long enough to be one ({@link LyricTimeline#INTERLUDE_SHOW_THRESHOLD_MS}).
     */
    static void collapseShortLrcGaps(LyricsDocument doc) {
        java.util.List<LyricsLine> lines = doc.lines;
        LyricsLine previousVocal = null;
        for (int i = 0; i < lines.size(); i++) {
            LyricsLine line = lines.get(i);
            if (!line.interlude) {
                previousVocal = line;
                continue;
            }
            if (previousVocal != null && previousVocal.endMs <= previousVocal.startMs
                    && line.startMs > previousVocal.startMs) {
                previousVocal.endMs = line.startMs;
            }
            long nextStart = -1;
            for (int j = i + 1; j < lines.size(); j++) {
                if (!lines.get(j).interlude) {
                    nextStart = lines.get(j).startMs;
                    break;
                }
            }
            // No vocal after it: the song's tail, handled by the end-of-song interlude rule.
            boolean tooShort = nextStart < 0
                    || nextStart - line.startMs < LyricTimeline.INTERLUDE_SHOW_THRESHOLD_MS;
            if (tooShort && previousVocal != null) {
                lines.remove(i);
                i--;
            }
        }
    }

    private void parsePlainLines(String plain, LyricsDocument doc) {
        if (isBlank(plain)) return;
        long cursor = 0;
        for (String rawLine : plain.split("\\r?\\n")) {
            String text = cleanInvisibles(rawLine);
            if (isBlank(text)) continue;
            LyricsLine line = new LyricsLine();
            line.text = text;
            line.startMs = cursor;
            line.endMs = cursor + 3500;
            cursor += 3500;
            applySecondaryText(line, new JsonObject());
            doc.lines.add(line);
        }
    }

    private void parseStatic(JsonObject data, LyricsDocument doc) {
        JsonArray lines = Json.optArray(data, "Lines", "lines");
        if (lines == null) return;
        long cursor = 0;
        for (JsonElement lineElement : lines) {
            if (!lineElement.isJsonObject()) continue;
            JsonObject object = lineElement.getAsJsonObject();
            String text = cleanInvisibles(Json.optString(object, "Text", "text"));
            if (isBlank(text)) continue;
            LyricsLine line = new LyricsLine();
            line.text = text;
            line.startMs = cursor;
            line.endMs = cursor + 3500;
            cursor += 3500;
            applySecondaryText(line, object);
            doc.lines.add(line);
        }
    }

    private void parseLine(JsonObject data, LyricsDocument doc) {
        JsonArray content = Json.optArray(data, "Content", "content");
        if (content == null) return;
        for (JsonElement item : content) {
            if (!item.isJsonObject()) continue;
            JsonObject object = item.getAsJsonObject();
            String type = Json.optString(object, "Type", "type");
            if (type == null || "Vocal".equalsIgnoreCase(type)) {
                String text = cleanInvisibles(Json.optString(object, "Text", "text"));
                if (isBlank(text)) continue;
                LyricsLine line = new LyricsLine();
                line.text = text;
                line.startMs = secondsToMs(Json.optDouble(object, 0d, "StartTime", "startTime"));
                line.endMs = secondsToMs(Json.optDouble(object, 0d, "EndTime", "endTime"));
                line.oppositeAligned = Json.optBoolean(object, false, "OppositeAligned", "oppositeAligned");
                applySecondaryText(line, object);
                doc.lines.add(line);
            } else if ("Interlude".equalsIgnoreCase(type)) {
                LyricsLine line = new LyricsLine();
                line.text = "♪";
                line.interlude = true;
                line.startMs = secondsToMs(Json.optDouble(object, 0d, "StartTime", "startTime"));
                line.endMs = secondsToMs(Json.optDouble(object, 0d, "EndTime", "endTime"));
                doc.lines.add(line);
            }
        }
    }

    private void parseSyllable(JsonObject data, LyricsDocument doc) {
        JsonArray content = Json.optArray(data, "Content", "content");
        if (content == null) return;
        for (JsonElement item : content) {
            if (!item.isJsonObject()) continue;
            JsonObject object = item.getAsJsonObject();
            String type = Json.optString(object, "Type", "type");
            if (type != null && !"Vocal".equalsIgnoreCase(type)) continue;
            JsonObject lead = Json.optObject(object, "Lead", "lead");
            if (lead == null) continue;
            JsonArray syllables = Json.optArray(lead, "Syllables", "syllables");
            String providerLineText = cleanInvisibles(firstNonBlank(
                    Json.optString(lead, "Text", "text"),
                    Json.optString(object, "Text", "text")));
            long lineStartMs = secondsToMs(Json.optDouble(lead,
                    Json.optDouble(object, 0d, "StartTime", "startTime"), "StartTime", "startTime"));
            long lineEndMs = secondsToMs(Json.optDouble(lead,
                    Json.optDouble(object, 0d, "EndTime", "endTime"), "EndTime", "endTime"));
            ParsedSyllableLine parsed = parseSyllableLine(syllables, providerLineText,
                    lineStartMs, lineEndMs, "line-" + lineStartMs + "-" + lineEndMs);
            if (parsed == null || isBlank(parsed.text)) continue;

            LyricsLine line = new LyricsLine();
            line.text = parsed.text;
            line.startMs = lineStartMs;
            line.endMs = lineEndMs;
            line.oppositeAligned = Json.optBoolean(object, false, "OppositeAligned", "oppositeAligned");
            line.syllables = parsed.segments;

            String transliterated = joinSecondarySyllables(syllables, line.syllables,
                    "TransliteratedText", "transliteratedText", "RomanizedText", "romanizedText");
            if (!isBlank(transliterated) && !transliterated.equals(line.text)) line.romanizedText = transliterated;
            String translated = joinSecondarySyllables(syllables, line.syllables,
                    "ProviderTranslatedText", "providerTranslatedText",
                    "TranslatedText", "translatedText", "Translation", "translation");
            captureProviderTranslation(line, translated, providerTranslationLanguage(lead));
            line.backgroundLines = parseBackgroundLines(object);
            applySecondaryText(line, lead);
            applySecondaryText(line, object);
            doc.lines.add(line);
        }
    }

    private void applySecondaryText(LyricsLine line, JsonObject object) {
        String providerRomanized = cleanInvisibles(firstNonBlank(
                Json.optString(object, "TransliteratedText", "transliteratedText"),
                Json.optString(object, "RomanizedText", "romanizedText", "RomanisedText", "romanisedText")
        ));
        if (!isBlank(providerRomanized) && !providerRomanized.equals(line.text) && !SpicyTextDetection.hasRomanizableScript(providerRomanized)) {
            line.romanizedText = providerRomanized;
        }

        String translated = cleanInvisibles(Json.optString(object,
                "ProviderTranslatedText", "providerTranslatedText",
                "TranslatedText", "translatedText", "Translation", "translation"));
        captureProviderTranslation(line, translated, providerTranslationLanguage(object));

        line.japaneseReading = SpicyJapaneseChineseProcessor.finalizeParsedJapaneseReading(
                parseJapaneseReading(object, line.text), line.text);
        if (line.japaneseReading != null && !line.japaneseReading.groups.isEmpty()
                && !isBlank(line.japaneseReading.romaji)) {
            line.romanizedText = line.japaneseReading.romaji;
        }
    }

    public static SpicyJapaneseChineseProcessor.JapaneseReading parseJapaneseReading(JsonObject object) {
        return parseJapaneseReading(object, "");
    }

    private static SpicyJapaneseChineseProcessor.JapaneseReading parseJapaneseReading(
            JsonObject object, String fallbackSourceText) {
        JsonObject reading = Json.optObject(object, "JapaneseReading", "japaneseReading");
        if (reading == null) return null;
        String sourceText = Json.optString(reading, "sourceText", "SourceText");
        if (isBlank(sourceText)) sourceText = fallbackSourceText;
        String romaji = Json.optString(reading, "romaji", "Romaji");
        JsonArray furiganaArray = Json.optArray(reading, "furigana", "Furigana");
        ArrayList<SpicyJapaneseChineseProcessor.FuriganaSegment> furigana = new ArrayList<>();
        if (furiganaArray != null) {
            for (JsonElement segmentElement : furiganaArray) {
                if (!segmentElement.isJsonObject()) continue;
                JsonObject segment = segmentElement.getAsJsonObject();
                int start = (int) Json.optDouble(segment, 0d, "start", "Start");
                int end = (int) Json.optDouble(segment, 0d, "end", "End");
                String kana = Json.optString(segment, "reading", "Reading");
                if (isBlank(kana) || end <= start) continue;
                furigana.add(new SpicyJapaneseChineseProcessor.FuriganaSegment(start, end, kana));
            }
        }
        JsonArray groupsArray = Json.optArray(reading, "groups", "Groups");
        ArrayList<SpicyJapaneseChineseProcessor.ReadingGroup> groups = new ArrayList<>();
        if (groupsArray != null) {
            for (JsonElement groupElement : groupsArray) {
                if (!groupElement.isJsonObject()) continue;
                JsonObject group = groupElement.getAsJsonObject();
                int start = (int) Json.optDouble(group, 0d, "start", "Start");
                int end = (int) Json.optDouble(group, 0d, "end", "End");
                String groupRomaji = Json.optString(group, "romaji", "Romaji");
                if (isBlank(groupRomaji) || end <= start) continue;
                groups.add(new SpicyJapaneseChineseProcessor.ReadingGroup(start, end, groupRomaji));
            }
        }
        if (isBlank(sourceText) && isBlank(romaji) && furigana.isEmpty()) return null;
        return SpicyJapaneseChineseProcessor.finalizeParsedJapaneseReading(
                new SpicyJapaneseChineseProcessor.JapaneseReading(sourceText, romaji, furigana, groups));
    }

    private static List<BackgroundLine> parseBackgroundLines(JsonObject object) {
        ArrayList<BackgroundLine> out = new ArrayList<>();
        JsonArray backgrounds = Json.optArray(object, "Background", "background");
        if (backgrounds == null || backgrounds.isEmpty()) return out;
        for (JsonElement element : backgrounds) {
            if (!element.isJsonObject()) continue;
            JsonObject bg = element.getAsJsonObject();
            JsonArray syllables = Json.optArray(bg, "Syllables", "syllables");
            if (syllables == null || syllables.isEmpty()) continue;
            BackgroundLine line = new BackgroundLine();
            line.startMs = secondsToMs(Json.optDouble(bg, 0d, "StartTime", "startTime"));
            line.endMs = secondsToMs(Json.optDouble(bg, line.startMs / 1000d, "EndTime", "endTime"));
            ParsedSyllableLine parsed = parseSyllableLine(syllables,
                    Json.optString(bg, "Text", "text"), line.startMs, line.endMs,
                    "background-" + line.startMs + "-" + line.endMs);
            if (parsed == null) continue;
            line.syllables = parsed.segments;
            line.text = parsed.text;
            line.romanizedText = firstNonBlank(
                    joinSecondarySyllables(syllables, line.syllables, "TransliteratedText", "transliteratedText"),
                    joinSecondarySyllables(syllables, line.syllables, "RomanizedText", "romanizedText")
            );
            line.providerTranslatedText = firstNonBlank(
                    cleanInvisibles(Json.optString(bg,
                            "ProviderTranslatedText", "providerTranslatedText",
                            "TranslatedText", "translatedText", "Translation", "translation")),
                    joinSecondarySyllables(syllables, line.syllables,
                            "ProviderTranslatedText", "providerTranslatedText",
                            "TranslatedText", "translatedText", "Translation", "translation")
            );
            line.providerTranslationLanguage = providerTranslationLanguage(bg);
            out.add(line);
        }
        return out;
    }

    private static void captureProviderTranslation(LyricsLine line, String translated, String language) {
        if (line == null) return;
        String value = cleanInvisibles(translated);
        if (isBlank(line.providerTranslatedText) && !isBlank(value) && !value.equals(line.text)) {
            line.providerTranslatedText = value;
        }
        if (isBlank(line.providerTranslationLanguage) && !isBlank(language)) {
            line.providerTranslationLanguage = language;
        }
    }

    private static String providerTranslationLanguage(JsonObject object) {
        if (object == null) return "";
        return cleanInvisibles(Json.optString(object,
                "ProviderTranslationLanguage", "providerTranslationLanguage",
                "TranslatedTextLanguage", "translatedTextLanguage",
                "TranslationLanguage", "translationLanguage"));
    }

    private static ParsedSyllableLine parseSyllableLine(JsonArray syllables, String providerLine,
                                                        long lineStartMs, long lineEndMs, String lineId) {
        if (syllables == null || syllables.isEmpty()) return null;
        ArrayList<SyllableSegment> segments = new ArrayList<>();
        long fallbackDuration = Math.max(1, lineEndMs - lineStartMs);
        long fallbackStep = Math.max(1, fallbackDuration / Math.max(1, syllables.size()));
        for (int i = 0; i < syllables.size(); i++) {
            JsonElement element = syllables.get(i);
            if (!element.isJsonObject()) continue;
            JsonObject syllable = element.getAsJsonObject();
            String rawText = cleanSyllableTextPreserveEdges(Json.optString(syllable, "Text", "text"));
            if (isBlank(rawText)) continue;
            String text = rawText.trim();
            if (text.isEmpty()) continue;
            SyllableSegment seg = new SyllableSegment();
            seg.spanId = String.valueOf(i);
            seg.text = text;
            seg.sourceText = rawText;
            seg.providerPartOfWord = providerPartOfWord(syllable);
            long fallbackStart = lineStartMs + fallbackStep * i;
            long fallbackEnd = i == syllables.size() - 1 ? lineEndMs : fallbackStart + fallbackStep;
            seg.startMs = secondsToMs(Json.optDouble(syllable, fallbackStart / 1000d, "StartTime", "startTime"));
            seg.endMs = secondsToMs(Json.optDouble(syllable, fallbackEnd / 1000d, "EndTime", "endTime"));
            if (seg.endMs <= seg.startMs) seg.endMs = Math.min(lineEndMs, seg.startMs + fallbackStep);
            if (seg.endMs <= seg.startMs) seg.endMs = seg.startMs + 1;
            seg.totalMs = Math.max(0, seg.endMs - seg.startMs);
            segments.add(seg);
        }
        if (segments.isEmpty()) return null;
        CanonicalLine canonical = SyllableCanonicalizer.canonicalize(lineId, providerLine, segments);
        return new ParsedSyllableLine(SyllableCanonicalizer.displayText(canonical, segments), segments);
    }

    private static String cleanSyllableTextPreserveEdges(String value) {
        if (value == null) return "";
        return value
                .replace("\u200B", "")
                .replace("\uFEFF", "")
                .replace('\u00A0', ' ')
                .replaceAll("[ \t]{2,}", " ");
    }

    private static Boolean providerPartOfWord(JsonObject syllable) {
        if (syllable == null) return null;
        if (syllable.has("IsPartOfWord")) return syllable.get("IsPartOfWord").getAsBoolean();
        if (syllable.has("isPartOfWord")) return syllable.get("isPartOfWord").getAsBoolean();
        return null;
    }

    private static String joinSecondarySyllables(JsonArray syllables, List<SyllableSegment> segments,
                                                  String... textKeys) {
        if (syllables == null || segments == null) return "";
        StringBuilder out = new StringBuilder();
        for (SyllableSegment segment : segments) {
            int sourceIndex;
            try {
                sourceIndex = Integer.parseInt(segment.spanId);
            } catch (NumberFormatException ignored) {
                continue;
            }
            if (sourceIndex < 0 || sourceIndex >= syllables.size()) continue;
            JsonElement element = syllables.get(sourceIndex);
            if (!element.isJsonObject()) continue;
            JsonObject syllable = element.getAsJsonObject();
            String text = cleanInvisibles(Json.optString(syllable, textKeys));
            if (isBlank(text)) continue;
            if (out.length() > 0 && !Character.isWhitespace(out.codePointBefore(out.length()))) {
                SyllableSegment previous = previousNonBlankSegment(segments, segment, syllables, textKeys);
                if (previous != null && previous.boundaryAfter) out.append(' ');
            }
            out.append(text);
        }
        return out.toString().trim();
    }

    private static SyllableSegment previousNonBlankSegment(List<SyllableSegment> segments,
                                                           SyllableSegment current,
                                                           JsonArray syllables, String... textKeys) {
        int currentIndex = segments.indexOf(current);
        for (int index = currentIndex - 1; index >= 0; index--) {
            SyllableSegment candidate = segments.get(index);
            int sourceIndex;
            try {
                sourceIndex = Integer.parseInt(candidate.spanId);
            } catch (NumberFormatException ignored) {
                continue;
            }
            if (sourceIndex < 0 || sourceIndex >= syllables.size()) continue;
            JsonElement element = syllables.get(sourceIndex);
            if (element.isJsonObject() && !isBlank(Json.optString(element.getAsJsonObject(), textKeys))) {
                return candidate;
            }
        }
        return null;
    }

    private static final class ParsedSyllableLine {
        final String text;
        final List<SyllableSegment> segments;
        ParsedSyllableLine(String text, List<SyllableSegment> segments) {
            this.text = text;
            this.segments = segments;
        }
    }

    private static JsonElement unpackSpicyPayloads(JsonElement element, SpicyResponseMetadata metadata, boolean queryResultData) {
        if (element == null || element.isJsonNull()) return element;
        if (SpicyObjPack.isPackedPayload(element)) {
            if (queryResultData) metadata.packedPayload = true;
            JsonElement unpacked = SpicyObjPack.unpack(element);
            log(TAG + " unpacked SLObjPack Spicy payload");
            return unpacked;
        }
        if (element.isJsonArray()) {
            JsonArray out = new JsonArray();
            for (JsonElement child : element.getAsJsonArray()) out.add(unpackSpicyPayloads(child, metadata, false));
            return out;
        }
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            boolean resultObject = isSpicyQueryResultObject(object);
            if (resultObject) captureSpicyQueryMetadata(object, metadata);
            JsonObject out = new JsonObject();
            for (String key : object.keySet()) {
                boolean resultData = resultObject && ("data".equals(key) || "Data".equals(key));
                out.add(key, unpackSpicyPayloads(object.get(key), metadata, resultData));
            }
            return out;
        }
        return element;
    }

    private static boolean isSpicyQueryResultObject(JsonObject object) {
        return Json.optElement(object, "data", "Data") != null
                && (Json.optElement(object, "httpStatus", "HttpStatus", "status", "Status") != null
                || Json.optElement(object, "format", "Format") != null);
    }

    private static void captureSpicyQueryMetadata(JsonObject result, SpicyResponseMetadata metadata) {
        if (metadata.queryStatus == null) metadata.queryStatus = optInteger(result, "httpStatus", "HttpStatus", "status", "Status");
        if (metadata.format == null) {
            String format = Json.optString(result, "format", "Format");
            if (!isBlank(format)) metadata.format = format;
        }
    }

    private static Integer optInteger(JsonObject object, String... keys) {
        JsonElement element = Json.optElement(object, keys);
        if (element == null) return null;
        try {
            return element.getAsInt();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void log(String message) {
        try {
            XpLog.log(message);
        } catch (Throwable ignored) {
        }
    }

    private static final class SpicyResponseMetadata {
        boolean packedPayload;
        Integer queryStatus;
        String format;
    }

    private static JsonObject findLyricsData(JsonElement element) {
        if (element == null || element.isJsonNull()) return null;
        JsonObject direct = findDirectLyricsData(element);
        if (direct != null) return direct;
        return findBestLyricsData(element, null);
    }

    private static JsonObject findDirectLyricsData(JsonElement element) {
        if (element == null || !element.isJsonObject()) return null;
        JsonArray queries = Json.optArray(element.getAsJsonObject(), "queries", "Queries");
        if (queries == null) return null;
        JsonObject best = null;
        int bestScore = Integer.MIN_VALUE;
        for (JsonElement queryElement : queries) {
            if (!queryElement.isJsonObject()) continue;
            JsonObject query = queryElement.getAsJsonObject();
            JsonObject result = Json.optObject(query, "result", "Result");
            if (result == null) continue;
            JsonObject data = Json.optObject(result, "data", "Data");
            JsonObject candidate = data == null ? findBestLyricsData(result, null) : findBestLyricsData(data, null);
            int score = lyricsDataScore(candidate);
            if (score > bestScore) {
                best = candidate;
                bestScore = score;
            }
        }
        return best;
    }

    private static JsonObject findBestLyricsData(JsonElement element, JsonObject best) {
        if (element == null || element.isJsonNull()) return best;
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            if (lyricsDataScore(object) > lyricsDataScore(best)) {
                best = object;
            }
            for (String key : object.keySet()) {
                best = findBestLyricsData(object.get(key), best);
            }
        } else if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                best = findBestLyricsData(child, best);
            }
        }
        return best;
    }

    private static int lyricsDataScore(JsonObject object) {
        if (object == null) return Integer.MIN_VALUE;
        String type = Json.optString(object, "Type", "type");
        int typeScore;
        if ("Syllable".equalsIgnoreCase(type)) typeScore = 300000;
        else if ("Line".equalsIgnoreCase(type)) typeScore = 200000;
        else if ("Static".equalsIgnoreCase(type)) typeScore = 100000;
        else return Integer.MIN_VALUE;

        int lineCount = 0;
        JsonArray lines = Json.optArray(object, "Lines", "lines", "Content", "content");
        if (lines != null) lineCount = lines.size();
        return typeScore + lineCount;
    }

    private void finalizeParsedDocument(Context context, LyricsDocument doc) {
        if (finalizer != null) finalizer.finalizeParsedDocument(context, doc);
    }

    private static String providerLabelFromSource(String source, String type) {
        String value = safe(source).trim();
        if (value.isEmpty()) return "Apple Music";
        if ("spt".equalsIgnoreCase(value)) return "Spotify";
        if ("aml".equalsIgnoreCase(value)) return "Apple Music";
        if ("spl".equalsIgnoreCase(value)) return "Spicy Lyrics";
        if ("ldb".equalsIgnoreCase(value)) return "Local DB";
        return value;
    }

    private static long secondsToMs(double seconds) {
        return Math.max(0, Math.round(seconds * 1000d));
    }

    private static long parseLongSafe(String value) {
        if (value == null) return 0;
        try {
            return Long.parseLong(value.trim());
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return "";
        for (String value : values) {
            if (!isBlank(value)) return value;
        }
        return "";
    }

    /** Join a SongWriters string array into a "A, B & C" credit line; "" if none. */
    private static String joinSongWriters(JsonArray array) {
        if (array == null || array.size() == 0) return "";
        java.util.ArrayList<String> names = new java.util.ArrayList<>();
        for (JsonElement el : array) {
            try {
                String name = el == null || el.isJsonNull() ? "" : el.getAsString().trim();
                if (!isBlank(name) && !names.contains(name)) names.add(name);
            } catch (Throwable ignored) {
            }
        }
        if (names.isEmpty()) return "";
        if (names.size() == 1) return names.get(0);
        String head = String.join(", ", names.subList(0, names.size() - 1));
        return head + " & " + names.get(names.size() - 1);
    }

    public interface Finalizer {
        void finalizeParsedDocument(Context context, LyricsDocument doc);
    }

    // --- NetEase and QQ Music (ported from PR #14, Musixmatch excluded) ---
    //
    // Word-level QRC/YRC documents report type "Word", not "Syllable": the timed tuples are
    // per-word units, and the renderer must fill them as words. QQ line payloads decode with
    // java.util.Base64 (identical output to android.util.Base64) so the JVM suite can cover them.

    /**
     * NetEase's legacy (unencrypted) {@code /api/song/lyric} response: a plain LRC block under
     * {@code lrc.lyric} plus an optional translated LRC block under {@code tlyric.lyric}.
     */
    @Override
    public LyricsDocument parseNeteaseLyrics(Context context, SpotifyTrack track, String body) {
        JsonElement root = JsonParser.parseString(body);
        if (!root.isJsonObject()) throw new IllegalStateException("no NetEase result");
        JsonObject object = root.getAsJsonObject();
        JsonObject lrcObject = Json.optObject(object, "lrc");
        String synced = lrcObject == null ? "" : Json.optString(lrcObject, "lyric");
        if (isBlank(synced)) throw new IllegalStateException("NetEase lyric empty");

        LyricsDocument doc = new LyricsDocument();
        doc.trackId = trackIdFromUri(track == null ? "" : track.uri);
        doc.durationMs = track == null ? 0 : Math.max(0, track.duration);
        doc.fetchSource = "netease";
        doc.provider = "NetEase";
        doc.language = "";
        doc.type = "Line";
        String[] credits = new String[1];
        synced = stripCreditLines(synced, credits);
        doc.songWriters = credits[0] == null ? "" : credits[0];
        parseLrcLines(synced, doc);

        JsonObject tlyricObject = Json.optObject(object, "tlyric");
        String translated = tlyricObject == null ? "" : Json.optString(tlyricObject, "lyric");
        if (!isBlank(translated)) applyNeteaseTranslation(doc, translated);

        if (markInstrumentalPlaceholder(doc)) return doc;
        finalizeParsedDocument(context, doc);
        return doc;
    }

    private static final Pattern YRC_LINE_HEADER = Pattern.compile("^\\[(\\d+),(\\d+)\\](.*)$");
    private static final Pattern YRC_WORD = Pattern.compile("\\((\\d+),(\\d+)(?:,-?\\d+)?\\)([^(]*)");

    /**
     * NetEase word-level ("YRC") lyrics: {@code [lineStart,lineDuration]} plus one
     * {@code (wordStart,wordDuration,0)} tuple per word.
     *
     * @return null when the response carries no usable word-level content at all, so the caller
     * can fall back to the line-level endpoint; a non-null document may still legitimately have
     * zero lines (an instrumental), same as any other source.
     */
    @Override
    public LyricsDocument parseNeteaseWordLyrics(Context context, SpotifyTrack track, String body) {
        JsonElement root = JsonParser.parseString(body);
        if (!root.isJsonObject()) return null;
        JsonObject object = root.getAsJsonObject();
        JsonObject yrcObject = Json.optObject(object, "yrc");
        String yrc = yrcObject == null ? "" : Json.optString(yrcObject, "lyric");
        if (isBlank(yrc)) return null;

        LyricsDocument doc = new LyricsDocument();
        doc.trackId = trackIdFromUri(track == null ? "" : track.uri);
        doc.durationMs = track == null ? 0 : Math.max(0, track.duration);
        doc.fetchSource = "netease";
        doc.provider = "NetEase";
        doc.language = "";
        doc.type = "Word";

        java.util.LinkedHashSet<String> writers = new java.util.LinkedHashSet<>();
        for (String rawLine : yrc.split("\\r?\\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) continue;
            if (line.charAt(0) == '{') {
                collectYrcCredits(line, writers);
                continue;
            }
            Matcher header = YRC_LINE_HEADER.matcher(line);
            if (!header.matches()) continue;
            long lineStartMs = parseLongSafe(header.group(1));
            long lineDurationMs = parseLongSafe(header.group(2));
            long lineEndMs = lineStartMs + Math.max(0, lineDurationMs);

            JsonArray syllables = new JsonArray();
            StringBuilder providerLine = new StringBuilder();
            Matcher word = YRC_WORD.matcher(header.group(3));
            while (word.find()) {
                String rawText = cleanInvisiblesPreserveEdges(word.group(3));
                if (rawText.isEmpty()) continue;
                providerLine.append(rawText);
                String text = rawText.trim();
                if (text.isEmpty()) continue;
                long startMs = parseLongSafe(word.group(1));
                long durationMs = parseLongSafe(word.group(2));
                JsonObject syllable = new JsonObject();
                syllable.addProperty("Text", text);
                syllable.addProperty("StartTime", startMs / 1000d);
                syllable.addProperty("EndTime", (startMs + Math.max(1, durationMs)) / 1000d);
                syllables.add(syllable);
            }
            if (syllables.size() == 0) continue;

            ParsedSyllableLine parsed = parseSyllableLine(syllables, providerLine.toString(),
                    lineStartMs, lineEndMs, "netease-yrc-" + lineStartMs);
            if (parsed == null || isBlank(parsed.text)) continue;
            LyricsLine syncedLine = new LyricsLine();
            syncedLine.text = parsed.text;
            syncedLine.startMs = lineStartMs;
            syncedLine.endMs = lineEndMs;
            syncedLine.syllables = parsed.segments;
            applySecondaryText(syncedLine, new JsonObject());
            doc.lines.add(syncedLine);
        }
        if (!writers.isEmpty()) doc.songWriters = String.join(", ", writers);
        markInstrumentalPlaceholder(doc);
        if (doc.lines.isEmpty()) return null;

        JsonObject ytlrcObject = Json.optObject(object, "ytlrc", "tlyric");
        String translated = ytlrcObject == null ? "" : Json.optString(ytlrcObject, "lyric");
        if (!isBlank(translated)) applyNeteaseTranslation(doc, translated);

        finalizeParsedDocument(context, doc);
        return doc;
    }

    /** Pulls writer names out of a YRC credit block: {@code {"t":0,"c":[{"tx":"作词: "},...]}}. */
    private static void collectYrcCredits(String jsonLine, java.util.Set<String> writers) {
        try {
            JsonElement parsed = JsonParser.parseString(jsonLine);
            if (!parsed.isJsonObject()) return;
            JsonArray parts = Json.optArray(parsed.getAsJsonObject(), "c");
            if (parts == null || parts.size() == 0) return;
            String first = parts.get(0).isJsonObject()
                    ? Json.optString(parts.get(0).getAsJsonObject(), "tx") : "";
            if (!first.startsWith("作词")) return;
            for (int i = 1; i < parts.size(); i++) {
                if (!parts.get(i).isJsonObject()) continue;
                String name = cleanInvisibles(
                        Json.optString(parts.get(i).getAsJsonObject(), "tx")).trim();
                if (isBlank(name) || "/".equals(name)) continue;
                writers.add(name);
            }
        } catch (Throwable ignored) {
            // A malformed credit block is not worth failing an otherwise good lyric over.
        }
    }

    @Override
    public LyricsDocument parseQqMusicLyrics(Context context, SpotifyTrack track, String body) {
        JsonElement root = JsonParser.parseString(body);
        if (!root.isJsonObject()) throw new IllegalStateException("no QQ Music result");
        JsonObject obj = root.getAsJsonObject();
        String lyricB64 = Json.optString(obj, "lyric");
        String transB64 = Json.optString(obj, "trans");
        String synced = "";
        String translated = "";
        try {
            if (!isBlank(lyricB64)) {
                synced = new String(java.util.Base64.getMimeDecoder().decode(lyricB64),
                        java.nio.charset.StandardCharsets.UTF_8);
            }
            if (!isBlank(transB64)) {
                translated = new String(java.util.Base64.getMimeDecoder().decode(transB64),
                        java.nio.charset.StandardCharsets.UTF_8);
            }
        } catch (Throwable ignored) { }
        if (isBlank(synced)) throw new IllegalStateException("QQ Music lyric empty");

        LyricsDocument doc = new LyricsDocument();
        doc.trackId = trackIdFromUri(track == null ? "" : track.uri);
        doc.durationMs = track == null ? 0 : Math.max(0, track.duration);
        doc.fetchSource = "qq_music";
        doc.provider = "QQ Music";
        doc.language = "";
        doc.type = "Line";
        String[] credits = new String[1];
        synced = stripCreditLines(synced, credits);
        doc.songWriters = credits[0] == null ? "" : credits[0];
        parseLrcLines(synced, doc);

        if (!isBlank(translated)) {
            java.util.Map<Long, String> byTimestamp = new java.util.LinkedHashMap<>();
            for (String rawLine : translated.split("\\r?\\n")) {
                Matcher matcher = LRC_TIMESTAMP.matcher(cleanInvisibles(rawLine));
                if (!matcher.matches()) continue;
                String text = cleanInvisibles(matcher.group(4));
                if (isBlank(text)) continue;
                byTimestamp.put(lrcTimestampMs(matcher), text);
            }
            if (!byTimestamp.isEmpty()) {
                for (LyricsLine line : doc.lines) {
                    String t = byTimestamp.get(line.startMs);
                    if (t != null && !t.equals(line.text)) line.providerTranslatedText = t;
                }
            }
        }

        if (markInstrumentalPlaceholder(doc)) return doc;
        finalizeParsedDocument(context, doc);
        return doc;
    }

    private static final byte[] QQ_QRC_KEY =
            "!@#)(*$%123ZXC!@!@#)(NHL".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    private static final Pattern QQ_QRC_CONTENT_TAG =
            Pattern.compile("<content\\b[^>]*>(?:<!\\[CDATA\\[)?([0-9a-fA-F]+)(?:]]>)?</content>");
    private static final Pattern QQ_QRC_TRANS_TAG =
            Pattern.compile("<contentts\\b[^>]*>(?:<!\\[CDATA\\[)?([0-9a-fA-F]+)(?:]]>)?</contentts>");
    private static final Pattern QQ_QRC_LYRIC_ATTR = Pattern.compile("<Lyric_1[^>]*\\bLyricContent=\"([^\"]*)\"");
    private static final Pattern QQ_QRC_LINE_HEADER = Pattern.compile("^\\[(\\d+),(\\d+)\\](.*)$");
    private static final Pattern QQ_QRC_WORD = Pattern.compile("(.*?)\\((\\d+),(\\d+)\\)");

    /** @return null if the response has no decryptable word-level content at all (caller falls
     *  back to the line-level endpoint); a non-null document may still legitimately have zero
     *  lines (e.g. an instrumental), same as any other source. */
    @Override
    public LyricsDocument parseQqWordLyrics(Context context, SpotifyTrack track, String rawResponse) {
        String body = rawResponse == null ? "" : rawResponse.replace("<!--", "").replace("-->", "");
        String qrcText = decryptQqQrcField(QQ_QRC_CONTENT_TAG, body);
        if (isBlank(qrcText)) return null;

        LyricsDocument doc = new LyricsDocument();
        doc.trackId = trackIdFromUri(track == null ? "" : track.uri);
        doc.durationMs = track == null ? 0 : Math.max(0, track.duration);
        doc.fetchSource = "qq_music";
        doc.provider = "QQ Music";
        doc.language = "";
        doc.type = "Word";

        CreditLineScanner credits = new CreditLineScanner();
        for (String rawLine : qrcText.split("\\r?\\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) continue;
            Matcher header = QQ_QRC_LINE_HEADER.matcher(line);
            if (!header.matches()) continue;
            long lineStartMs = parseLongSafe(header.group(1));
            long lineDurationMs = parseLongSafe(header.group(2));
            long lineEndMs = lineStartMs + Math.max(0, lineDurationMs);

            JsonArray syllables = new JsonArray();
            StringBuilder providerLine = new StringBuilder();
            Matcher word = QQ_QRC_WORD.matcher(header.group(3));
            while (word.find()) {
                String rawText = cleanInvisiblesPreserveEdges(word.group(1));
                if (rawText.isEmpty()) continue;
                providerLine.append(rawText);
                String text = rawText.trim();
                if (text.isEmpty()) continue;
                long startMs = parseLongSafe(word.group(2));
                long durationMs = parseLongSafe(word.group(3));
                JsonObject syllable = new JsonObject();
                syllable.addProperty("Text", text);
                syllable.addProperty("StartTime", startMs / 1000d);
                syllable.addProperty("EndTime", (startMs + Math.max(1, durationMs)) / 1000d);
                syllables.add(syllable);
            }
            if (syllables.size() == 0) continue;
            String candidateLine = providerLine.toString().trim();
            if (isQqTitleCard(candidateLine, track)) continue;
            if (credits.consume(candidateLine)) continue;

            ParsedSyllableLine parsed = parseSyllableLine(syllables, providerLine.toString(),
                    lineStartMs, lineEndMs, "qq-qrc-" + lineStartMs);
            if (parsed == null || isBlank(parsed.text)) continue;
            LyricsLine syncedLine = new LyricsLine();
            syncedLine.text = parsed.text;
            syncedLine.startMs = lineStartMs;
            syncedLine.endMs = lineEndMs;
            syncedLine.syllables = parsed.segments;
            applySecondaryText(syncedLine, new JsonObject());
            doc.lines.add(syncedLine);
        }
        doc.songWriters = credits.credits() == null ? "" : credits.credits();
        markInstrumentalPlaceholder(doc);
        if (doc.lines.isEmpty()) return null;

        String transText = decryptQqQrcField(QQ_QRC_TRANS_TAG, body);
        if (!isBlank(transText)) applyQqQrcTranslation(doc, transText);

        finalizeParsedDocument(context, doc);
        return doc;
    }

    /** A provider's "instrumental, enjoy" placeholder is not lyrics: record the track and hand
     *  back an empty document, so another source still gets a chance. Scoped to the QQ/NetEase
     *  parsers; other sources keep their existing behavior. */
    private static boolean markInstrumentalPlaceholder(LyricsDocument doc) {
        if (doc != null && InstrumentalTracks.isPlaceholderOnly(doc.lines)) {
            InstrumentalTracks.mark(doc.trackId);
            doc.lines.clear();
            return true;
        }
        return false;
    }

    /** Translations ride along as an ordinary sentence-level LRC block even when the main lyric is
     *  word-timed, so this matches by nearest timestamp rather than exact equality. */
    private static void applyQqQrcTranslation(LyricsDocument doc, String translatedLrc) {
        java.util.List<long[]> starts = new ArrayList<>();
        java.util.List<String> texts = new ArrayList<>();
        for (String rawLine : translatedLrc.split("\\r?\\n")) {
            Matcher matcher = LRC_TIMESTAMP.matcher(cleanInvisibles(rawLine));
            if (!matcher.matches()) continue;
            String text = cleanInvisibles(matcher.group(4));
            if (isBlank(text)) continue;
            starts.add(new long[]{lrcTimestampMs(matcher)});
            texts.add(text);
        }
        if (texts.isEmpty()) return;
        for (LyricsLine line : doc.lines) {
            int bestIndex = -1;
            long bestDiff = 1500;
            for (int i = 0; i < starts.size(); i++) {
                long diff = Math.abs(starts.get(i)[0] - line.startMs);
                if (diff < bestDiff) {
                    bestDiff = diff;
                    bestIndex = i;
                }
            }
            if (bestIndex >= 0) line.providerTranslatedText = texts.get(bestIndex);
        }
    }

    /** Extracts, hex-decodes, decrypts and inflates the named field ("content"/"contentts"),
     *  unwrapping the optional inner {@code <Lyric_1 LyricContent="...">} XML layer. Returns "" on
     *  any failure (missing field, bad key, corrupt stream) rather than throwing - every caller
     *  treats that as "this field isn't usable", not a hard error. */
    private static String decryptQqQrcField(Pattern tagPattern, String body) {
        try {
            Matcher tag = tagPattern.matcher(body);
            if (!tag.find()) return "";
            byte[] encrypted = hexDecode(tag.group(1));
            byte[] decrypted = tripleDesEcbDecrypt(encrypted, QQ_QRC_KEY);
            byte[] inflated = zlibInflate(decrypted);
            String text = new String(inflated, java.nio.charset.StandardCharsets.UTF_8);
            if (!text.isEmpty() && text.charAt(0) == '﻿') text = text.substring(1);
            if (text.contains("<?xml")) {
                Matcher attr = QQ_QRC_LYRIC_ATTR.matcher(text);
                if (!attr.find()) return "";
                text = unescapeXmlEntities(attr.group(1));
            }
            return text;
        } catch (Throwable t) {
            return "";
        }
    }

    private static byte[] hexDecode(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static byte[] tripleDesEcbDecrypt(byte[] data, byte[] key24) {
        byte[][][] schedule = new byte[3][16][6];
        QqDes.tripleDesKeySetup(key24, schedule, QqDes.DECRYPT);
        byte[] out = new byte[data.length];
        byte[] block = new byte[8];
        for (int i = 0; i + 8 <= data.length; i += 8) {
            System.arraycopy(data, i, block, 0, 8);
            byte[] temp = QqDes.tripleDesCrypt(block, schedule);
            System.arraycopy(temp, 0, out, i, 8);
        }
        return out;
    }

    /** Test seam: builds a decryptable QRC field response for {@link #parseQqWordLyrics}. */
    static String buildQqQrcResponseForTest(String lyricContent, String transContent) {
        try {
            String content = hexEncode(tripleDesEcbEncrypt(
                    zlibDeflate(wrapLyricXml(lyricContent).getBytes(
                            java.nio.charset.StandardCharsets.UTF_8)), QQ_QRC_KEY));
            StringBuilder out = new StringBuilder("<content>");
            out.append(content).append("</content>");
            if (transContent != null) {
                String trans = hexEncode(tripleDesEcbEncrypt(
                        zlibDeflate(transContent.getBytes(
                                java.nio.charset.StandardCharsets.UTF_8)), QQ_QRC_KEY));
                out.append("<contentts>").append(trans).append("</contentts>");
            }
            return out.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    private static String wrapLyricXml(String lyricContent) {
        return "<?xml version=\"1.0\"?><Lyric><Lyric_1 LyricContent=\""
                + lyricContent.replace("&", "&amp;").replace("\"", "&quot;") + "\"/></Lyric>";
    }

    private static byte[] zlibDeflate(byte[] data) throws Exception {
        java.util.zip.Deflater deflater = new java.util.zip.Deflater();
        deflater.setInput(data);
        deflater.finish();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(data.length);
        byte[] buffer = new byte[4096];
        while (!deflater.finished()) {
            int count = deflater.deflate(buffer);
            out.write(buffer, 0, count);
        }
        deflater.end();
        return out.toByteArray();
    }

    private static byte[] tripleDesEcbEncrypt(byte[] data, byte[] key24) {
        byte[][][] schedule = new byte[3][16][6];
        QqDes.tripleDesKeySetup(key24, schedule, QqDes.ENCRYPT);
        int padded = (data.length + 7) / 8 * 8;
        byte[] out = new byte[padded];
        byte[] block = new byte[8];
        for (int i = 0; i < padded; i += 8) {
            int copy = Math.min(8, data.length - i);
            if (copy > 0) System.arraycopy(data, i, block, 0, copy);
            for (int j = copy; j < 8; j++) block[j] = 0;
            byte[] temp = QqDes.tripleDesCrypt(block, schedule);
            System.arraycopy(temp, 0, out, i, 8);
        }
        return out;
    }

    private static String hexEncode(byte[] data) {
        StringBuilder out = new StringBuilder(data.length * 2);
        for (byte b : data) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xFF));
        return out.toString();
    }

    /**
     * Direct Java port of Lyricify-Lyrics-Helper's DESHelper.cs - QQ's QRC field is encrypted
     * with a hand-rolled TripleDES-ECB rather than anything the platform JCE necessarily agrees
     * bit-for-bit with, so this ports the verified reference implementation instead of trusting
     * javax.crypto's "DESede" to match it.
     */
    private static final class QqDes {
        static final int ENCRYPT = 1;
        static final int DECRYPT = 0;

        private static int bitnum(byte[] a, int b, int c) {
            return (((a[b / 32 * 4 + 3 - b % 32 / 8] & 0xFF) >>> (7 - (b % 8))) & 0x01) << c;
        }

        private static int bitnumIntR(int a, int b, int c) {
            return (((a >>> (31 - b)) & 0x00000001) << c);
        }

        private static int bitnumIntL(int a, int b, int c) {
            return ((a << b) & 0x80000000) >>> c;
        }

        private static int sboxbit(int a) {
            return (a & 0x20) | ((a & 0x1f) >>> 1) | ((a & 0x01) << 4);
        }

        private static final int[] SBOX1 = {
                14, 4, 13, 1, 2, 15, 11, 8, 3, 10, 6, 12, 5, 9, 0, 7,
                0, 15, 7, 4, 14, 2, 13, 1, 10, 6, 12, 11, 9, 5, 3, 8,
                4, 1, 14, 8, 13, 6, 2, 11, 15, 12, 9, 7, 3, 10, 5, 0,
                15, 12, 8, 2, 4, 9, 1, 7, 5, 11, 3, 14, 10, 0, 6, 13
        };
        private static final int[] SBOX2 = {
                15, 1, 8, 14, 6, 11, 3, 4, 9, 7, 2, 13, 12, 0, 5, 10,
                3, 13, 4, 7, 15, 2, 8, 15, 12, 0, 1, 10, 6, 9, 11, 5,
                0, 14, 7, 11, 10, 4, 13, 1, 5, 8, 12, 6, 9, 3, 2, 15,
                13, 8, 10, 1, 3, 15, 4, 2, 11, 6, 7, 12, 0, 5, 14, 9
        };
        private static final int[] SBOX3 = {
                10, 0, 9, 14, 6, 3, 15, 5, 1, 13, 12, 7, 11, 4, 2, 8,
                13, 7, 0, 9, 3, 4, 6, 10, 2, 8, 5, 14, 12, 11, 15, 1,
                13, 6, 4, 9, 8, 15, 3, 0, 11, 1, 2, 12, 5, 10, 14, 7,
                1, 10, 13, 0, 6, 9, 8, 7, 4, 15, 14, 3, 11, 5, 2, 12
        };
        private static final int[] SBOX4 = {
                7, 13, 14, 3, 0, 6, 9, 10, 1, 2, 8, 5, 11, 12, 4, 15,
                13, 8, 11, 5, 6, 15, 0, 3, 4, 7, 2, 12, 1, 10, 14, 9,
                10, 6, 9, 0, 12, 11, 7, 13, 15, 1, 3, 14, 5, 2, 8, 4,
                3, 15, 0, 6, 10, 10, 13, 8, 9, 4, 5, 11, 12, 7, 2, 14
        };
        private static final int[] SBOX5 = {
                2, 12, 4, 1, 7, 10, 11, 6, 8, 5, 3, 15, 13, 0, 14, 9,
                14, 11, 2, 12, 4, 7, 13, 1, 5, 0, 15, 10, 3, 9, 8, 6,
                4, 2, 1, 11, 10, 13, 7, 8, 15, 9, 12, 5, 6, 3, 0, 14,
                11, 8, 12, 7, 1, 14, 2, 13, 6, 15, 0, 9, 10, 4, 5, 3
        };
        private static final int[] SBOX6 = {
                12, 1, 10, 15, 9, 2, 6, 8, 0, 13, 3, 4, 14, 7, 5, 11,
                10, 15, 4, 2, 7, 12, 9, 5, 6, 1, 13, 14, 0, 11, 3, 8,
                9, 14, 15, 5, 2, 8, 12, 3, 7, 0, 4, 10, 1, 13, 11, 6,
                4, 3, 2, 12, 9, 5, 15, 10, 11, 14, 1, 7, 6, 0, 8, 13
        };
        private static final int[] SBOX7 = {
                4, 11, 2, 14, 15, 0, 8, 13, 3, 12, 9, 7, 5, 10, 6, 1,
                13, 0, 11, 7, 4, 9, 1, 10, 14, 3, 5, 12, 2, 15, 8, 6,
                1, 4, 11, 13, 12, 3, 7, 14, 10, 15, 6, 8, 0, 5, 9, 2,
                6, 11, 13, 8, 1, 4, 10, 7, 9, 5, 0, 15, 14, 2, 3, 12
        };
        private static final int[] SBOX8 = {
                13, 2, 8, 4, 6, 15, 11, 1, 10, 9, 3, 14, 5, 0, 12, 7,
                1, 15, 13, 8, 10, 3, 7, 4, 12, 5, 6, 11, 0, 14, 9, 2,
                7, 11, 4, 1, 9, 12, 14, 2, 0, 6, 10, 13, 15, 3, 5, 8,
                2, 1, 14, 7, 4, 10, 8, 13, 15, 12, 9, 0, 3, 5, 6, 11
        };

        private static void keySchedule(byte[] key, byte[][] schedule, int mode) {
            int[] keyRndShift = {1, 1, 2, 2, 2, 2, 2, 2, 1, 2, 2, 2, 2, 2, 2, 1};
            int[] keyPermC = {56, 48, 40, 32, 24, 16, 8, 0, 57, 49, 41, 33, 25, 17,
                    9, 1, 58, 50, 42, 34, 26, 18, 10, 2, 59, 51, 43, 35};
            int[] keyPermD = {62, 54, 46, 38, 30, 22, 14, 6, 61, 53, 45, 37, 29, 21,
                    13, 5, 60, 52, 44, 36, 28, 20, 12, 4, 27, 19, 11, 3};
            int[] keyCompression = {13, 16, 10, 23, 0, 4, 2, 27, 14, 5, 20, 9,
                    22, 18, 11, 3, 25, 7, 15, 6, 26, 19, 12, 1,
                    40, 51, 30, 36, 46, 54, 29, 39, 50, 44, 32, 47,
                    43, 48, 38, 55, 33, 52, 45, 41, 49, 35, 28, 31};

            int c = 0, d = 0;
            int j;
            for (int i = 0, jj = 31; i < 28; ++i, --jj) c |= bitnum(key, keyPermC[i], jj);
            for (int i = 0, jj = 31; i < 28; ++i, --jj) d |= bitnum(key, keyPermD[i], jj);

            for (int i = 0; i < 16; ++i) {
                c = ((c << keyRndShift[i]) | (c >>> (28 - keyRndShift[i]))) & 0xfffffff0;
                d = ((d << keyRndShift[i]) | (d >>> (28 - keyRndShift[i]))) & 0xfffffff0;

                int toGen = mode == DECRYPT ? 15 - i : i;

                for (j = 0; j < 6; ++j) schedule[toGen][j] = 0;

                for (j = 0; j < 24; ++j) {
                    schedule[toGen][j / 8] |= (byte) bitnumIntR(c, keyCompression[j], 7 - (j % 8));
                }
                for (; j < 48; ++j) {
                    schedule[toGen][j / 8] |= (byte) bitnumIntR(d, keyCompression[j] - 27, 7 - (j % 8));
                }
            }
        }

        private static void ip(int[] state, byte[] input) {
            state[0] = bitnum(input, 57, 31) | bitnum(input, 49, 30) | bitnum(input, 41, 29) | bitnum(input, 33, 28)
                    | bitnum(input, 25, 27) | bitnum(input, 17, 26) | bitnum(input, 9, 25) | bitnum(input, 1, 24)
                    | bitnum(input, 59, 23) | bitnum(input, 51, 22) | bitnum(input, 43, 21) | bitnum(input, 35, 20)
                    | bitnum(input, 27, 19) | bitnum(input, 19, 18) | bitnum(input, 11, 17) | bitnum(input, 3, 16)
                    | bitnum(input, 61, 15) | bitnum(input, 53, 14) | bitnum(input, 45, 13) | bitnum(input, 37, 12)
                    | bitnum(input, 29, 11) | bitnum(input, 21, 10) | bitnum(input, 13, 9) | bitnum(input, 5, 8)
                    | bitnum(input, 63, 7) | bitnum(input, 55, 6) | bitnum(input, 47, 5) | bitnum(input, 39, 4)
                    | bitnum(input, 31, 3) | bitnum(input, 23, 2) | bitnum(input, 15, 1) | bitnum(input, 7, 0);

            state[1] = bitnum(input, 56, 31) | bitnum(input, 48, 30) | bitnum(input, 40, 29) | bitnum(input, 32, 28)
                    | bitnum(input, 24, 27) | bitnum(input, 16, 26) | bitnum(input, 8, 25) | bitnum(input, 0, 24)
                    | bitnum(input, 58, 23) | bitnum(input, 50, 22) | bitnum(input, 42, 21) | bitnum(input, 34, 20)
                    | bitnum(input, 26, 19) | bitnum(input, 18, 18) | bitnum(input, 10, 17) | bitnum(input, 2, 16)
                    | bitnum(input, 60, 15) | bitnum(input, 52, 14) | bitnum(input, 44, 13) | bitnum(input, 36, 12)
                    | bitnum(input, 28, 11) | bitnum(input, 20, 10) | bitnum(input, 12, 9) | bitnum(input, 4, 8)
                    | bitnum(input, 62, 7) | bitnum(input, 54, 6) | bitnum(input, 46, 5) | bitnum(input, 38, 4)
                    | bitnum(input, 30, 3) | bitnum(input, 22, 2) | bitnum(input, 14, 1) | bitnum(input, 6, 0);
        }

        private static void invIp(int[] state, byte[] output) {
            output[3] = (byte) (bitnumIntR(state[1], 7, 7) | bitnumIntR(state[0], 7, 6) | bitnumIntR(state[1], 15, 5)
                    | bitnumIntR(state[0], 15, 4) | bitnumIntR(state[1], 23, 3) | bitnumIntR(state[0], 23, 2)
                    | bitnumIntR(state[1], 31, 1) | bitnumIntR(state[0], 31, 0));

            output[2] = (byte) (bitnumIntR(state[1], 6, 7) | bitnumIntR(state[0], 6, 6) | bitnumIntR(state[1], 14, 5)
                    | bitnumIntR(state[0], 14, 4) | bitnumIntR(state[1], 22, 3) | bitnumIntR(state[0], 22, 2)
                    | bitnumIntR(state[1], 30, 1) | bitnumIntR(state[0], 30, 0));

            output[1] = (byte) (bitnumIntR(state[1], 5, 7) | bitnumIntR(state[0], 5, 6) | bitnumIntR(state[1], 13, 5)
                    | bitnumIntR(state[0], 13, 4) | bitnumIntR(state[1], 21, 3) | bitnumIntR(state[0], 21, 2)
                    | bitnumIntR(state[1], 29, 1) | bitnumIntR(state[0], 29, 0));

            output[0] = (byte) (bitnumIntR(state[1], 4, 7) | bitnumIntR(state[0], 4, 6) | bitnumIntR(state[1], 12, 5)
                    | bitnumIntR(state[0], 12, 4) | bitnumIntR(state[1], 20, 3) | bitnumIntR(state[0], 20, 2)
                    | bitnumIntR(state[1], 28, 1) | bitnumIntR(state[0], 28, 0));

            output[7] = (byte) (bitnumIntR(state[1], 3, 7) | bitnumIntR(state[0], 3, 6) | bitnumIntR(state[1], 11, 5)
                    | bitnumIntR(state[0], 11, 4) | bitnumIntR(state[1], 19, 3) | bitnumIntR(state[0], 19, 2)
                    | bitnumIntR(state[1], 27, 1) | bitnumIntR(state[0], 27, 0));

            output[6] = (byte) (bitnumIntR(state[1], 2, 7) | bitnumIntR(state[0], 2, 6) | bitnumIntR(state[1], 10, 5)
                    | bitnumIntR(state[0], 10, 4) | bitnumIntR(state[1], 18, 3) | bitnumIntR(state[0], 18, 2)
                    | bitnumIntR(state[1], 26, 1) | bitnumIntR(state[0], 26, 0));

            output[5] = (byte) (bitnumIntR(state[1], 1, 7) | bitnumIntR(state[0], 1, 6) | bitnumIntR(state[1], 9, 5)
                    | bitnumIntR(state[0], 9, 4) | bitnumIntR(state[1], 17, 3) | bitnumIntR(state[0], 17, 2)
                    | bitnumIntR(state[1], 25, 1) | bitnumIntR(state[0], 25, 0));

            output[4] = (byte) (bitnumIntR(state[1], 0, 7) | bitnumIntR(state[0], 0, 6) | bitnumIntR(state[1], 8, 5)
                    | bitnumIntR(state[0], 8, 4) | bitnumIntR(state[1], 16, 3) | bitnumIntR(state[0], 16, 2)
                    | bitnumIntR(state[1], 24, 1) | bitnumIntR(state[0], 24, 0));
        }

        private static int f(int state, byte[] key) {
            byte[] lrg = new byte[6];

            int t1 = bitnumIntL(state, 31, 0) | ((state & 0xf0000000) >>> 1) | bitnumIntL(state, 4, 5)
                    | bitnumIntL(state, 3, 6) | ((state & 0x0f000000) >>> 3) | bitnumIntL(state, 8, 11)
                    | bitnumIntL(state, 7, 12) | ((state & 0x00f00000) >>> 5) | bitnumIntL(state, 12, 17)
                    | bitnumIntL(state, 11, 18) | ((state & 0x000f0000) >>> 7) | bitnumIntL(state, 16, 23);

            int t2 = bitnumIntL(state, 15, 0) | ((state & 0x0000f000) << 15) | bitnumIntL(state, 20, 5)
                    | bitnumIntL(state, 19, 6) | ((state & 0x00000f00) << 13) | bitnumIntL(state, 24, 11)
                    | bitnumIntL(state, 23, 12) | ((state & 0x000000f0) << 11) | bitnumIntL(state, 28, 17)
                    | bitnumIntL(state, 27, 18) | ((state & 0x0000000f) << 9) | bitnumIntL(state, 0, 23);

            lrg[0] = (byte) ((t1 >>> 24) & 0xff);
            lrg[1] = (byte) ((t1 >>> 16) & 0xff);
            lrg[2] = (byte) ((t1 >>> 8) & 0xff);
            lrg[3] = (byte) ((t2 >>> 24) & 0xff);
            lrg[4] = (byte) ((t2 >>> 16) & 0xff);
            lrg[5] = (byte) ((t2 >>> 8) & 0xff);

            lrg[0] ^= key[0];
            lrg[1] ^= key[1];
            lrg[2] ^= key[2];
            lrg[3] ^= key[3];
            lrg[4] ^= key[4];
            lrg[5] ^= key[5];

            state = (SBOX1[sboxbit((lrg[0] & 0xFF) >>> 2)] << 28)
                    | (SBOX2[sboxbit(((lrg[0] & 0xFF & 0x03) << 4) | ((lrg[1] & 0xFF) >>> 4))] << 24)
                    | (SBOX3[sboxbit(((lrg[1] & 0xFF & 0x0f) << 2) | ((lrg[2] & 0xFF) >>> 6))] << 20)
                    | (SBOX4[sboxbit(lrg[2] & 0xFF & 0x3f)] << 16)
                    | (SBOX5[sboxbit((lrg[3] & 0xFF) >>> 2)] << 12)
                    | (SBOX6[sboxbit(((lrg[3] & 0xFF & 0x03) << 4) | ((lrg[4] & 0xFF) >>> 4))] << 8)
                    | (SBOX7[sboxbit(((lrg[4] & 0xFF & 0x0f) << 2) | ((lrg[5] & 0xFF) >>> 6))] << 4)
                    | SBOX8[sboxbit(lrg[5] & 0xFF & 0x3f)];

            return bitnumIntL(state, 15, 0) | bitnumIntL(state, 6, 1) | bitnumIntL(state, 19, 2)
                    | bitnumIntL(state, 20, 3) | bitnumIntL(state, 28, 4) | bitnumIntL(state, 11, 5)
                    | bitnumIntL(state, 27, 6) | bitnumIntL(state, 16, 7) | bitnumIntL(state, 0, 8)
                    | bitnumIntL(state, 14, 9) | bitnumIntL(state, 22, 10) | bitnumIntL(state, 25, 11)
                    | bitnumIntL(state, 4, 12) | bitnumIntL(state, 17, 13) | bitnumIntL(state, 30, 14)
                    | bitnumIntL(state, 9, 15) | bitnumIntL(state, 1, 16) | bitnumIntL(state, 7, 17)
                    | bitnumIntL(state, 23, 18) | bitnumIntL(state, 13, 19) | bitnumIntL(state, 31, 20)
                    | bitnumIntL(state, 26, 21) | bitnumIntL(state, 2, 22) | bitnumIntL(state, 8, 23)
                    | bitnumIntL(state, 18, 24) | bitnumIntL(state, 12, 25) | bitnumIntL(state, 29, 26)
                    | bitnumIntL(state, 5, 27) | bitnumIntL(state, 21, 28) | bitnumIntL(state, 10, 29)
                    | bitnumIntL(state, 3, 30) | bitnumIntL(state, 24, 31);
        }

        private static byte[] crypt(byte[] input, byte[][] key) {
            byte[] output = new byte[8];
            int[] state = new int[2];
            ip(state, input);

            for (int idx = 0; idx < 15; ++idx) {
                int t = state[1];
                state[1] = f(state[1], key[idx]) ^ state[0];
                state[0] = t;
            }
            state[0] = f(state[1], key[15]) ^ state[0];

            invIp(state, output);
            return output;
        }

        static void tripleDesKeySetup(byte[] key, byte[][][] schedule, int mode) {
            if (mode == ENCRYPT) {
                keySchedule(java.util.Arrays.copyOfRange(key, 0, 8), schedule[0], mode);
                keySchedule(java.util.Arrays.copyOfRange(key, 8, 16), schedule[1], DECRYPT);
                keySchedule(java.util.Arrays.copyOfRange(key, 16, 24), schedule[2], mode);
            } else {
                keySchedule(java.util.Arrays.copyOfRange(key, 0, 8), schedule[2], mode);
                keySchedule(java.util.Arrays.copyOfRange(key, 8, 16), schedule[1], ENCRYPT);
                keySchedule(java.util.Arrays.copyOfRange(key, 16, 24), schedule[0], mode);
            }
        }

        static byte[] tripleDesCrypt(byte[] input, byte[][][] key) {
            byte[] out = crypt(input, key[0]);
            out = crypt(out, key[1]);
            out = crypt(out, key[2]);
            return out;
        }
    }

    private static byte[] zlibInflate(byte[] data) throws Exception {
        java.util.zip.Inflater inflater = new java.util.zip.Inflater();
        inflater.setInput(data);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(Math.max(64, data.length * 4));
        byte[] buffer = new byte[4096];
        while (!inflater.finished()) {
            int count = inflater.inflate(buffer);
            if (count == 0) {
                if (inflater.needsInput() || inflater.needsDictionary()) break;
            }
            out.write(buffer, 0, count);
        }
        inflater.end();
        return out.toByteArray();
    }

    private static String unescapeXmlEntities(String value) {
        return value.replace("&quot;", "\"").replace("&apos;", "'")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
    }

    private static final Pattern QQ_CREDIT_LABEL = Pattern.compile(
            "^(?:作?词|作?曲|作?詞|编曲|編曲|监制|監製|監制|制作人|製作人|制作|製作|出品|发行|發行|OP|SP"
                    + "|混音|混缩|母带|母帶|和声|和聲|吉他|贝斯|貝斯|鼓|弦乐|弦樂|录音|錄音|演唱|原唱"
                    + "|键盘|鍵盤|钢琴|鋼琴|配唱|企划|企劃|统筹|統籌|策划|策劃|出品人|版权|版權"
                    + "|Vocals?|Guitar|Bass|Drums|Keyboards?|Piano|Mix(?:ing)?|Master(?:ing)?|Recording"
                    + "|Lyrics?(?:\\s+by)?|Music(?:\\s+by)?|Words(?:\\s+by)?"
                    + "|Composed\\s+by|Arranged\\s+by|Produced\\s+by|Written\\s+by|Mixed\\s+by"
                    + "|Lyricist|Composer|Arranger|Producer)\\s*[:：]\\s*(.*)$",
            Pattern.CASE_INSENSITIVE);
    private static final int QQ_CREDIT_SCAN_WINDOW = 12;

    /** Shared "Composed by :" / "词：许嵩" style credit-block recognizer for both plain-LRC
     *  ({@link #stripCreditLines}) and QRC ({@link #parseQqWordLyrics}) provider text. */
    private static final class CreditLineScanner {
        private final java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        private boolean expectNamesLine;
        private int seen;

        /** @return true if {@code text} was consumed as (part of) a credit block and must not be
         *  rendered as a lyric line. */
        boolean consume(String text) {
            seen++;
            if (seen > QQ_CREDIT_SCAN_WINDOW) {
                expectNamesLine = false;
                return false;
            }
            Matcher label = QQ_CREDIT_LABEL.matcher(text);
            if (label.matches()) {
                addCreditNames(names, label.group(1));
                expectNamesLine = isBlank(label.group(1));
                return true;
            }
            if (expectNamesLine) {
                addCreditNames(names, text);
                expectNamesLine = false;
                return true;
            }
            expectNamesLine = false;
            return false;
        }

        String credits() {
            return names.isEmpty() ? null : String.join(", ", names);
        }
    }

    /**
     * Removes leading credit lines (songwriter/composer/arranger/producer, e.g. "词：许嵩") from a
     * raw LRC body so they don't get rendered as opening lyric lines, collecting the names into
     * {@code outCredits[0]} (comma-joined) for display as "Written by ..." instead, matching how
     * Apple's SongWriters field is surfaced.
     */
    private static String stripCreditLines(String synced, String[] outCredits) {
        CreditLineScanner scanner = new CreditLineScanner();
        StringBuilder filtered = new StringBuilder();
        for (String rawLine : synced.split("\\r?\\n")) {
            Matcher timestamp = LRC_TIMESTAMP.matcher(cleanInvisibles(rawLine));
            if (!timestamp.matches()) {
                filtered.append(rawLine).append('\n');
                continue;
            }
            String text = cleanInvisibles(timestamp.group(4));
            if (!scanner.consume(text)) filtered.append(rawLine).append('\n');
        }
        outCredits[0] = scanner.credits();
        return filtered.toString();
    }

    /** QQ QRC files commonly open with a decorative "{Title} - {Artist}" line before the real
     *  lyrics start (e.g. "Too Sweet - Hozier"), same category as the credit lines but not caught
     *  by that label matcher. Only strips an exact match against the real track's own metadata, so
     *  it can never mistake genuine lyric text (even lyrics containing " - ") for the title card. */
    private static boolean isQqTitleCard(String candidateLine, SpotifyTrack track) {
        if (track == null || isBlank(candidateLine)) return false;
        String norm = normalizeForTitleCard(candidateLine);
        if (norm.isEmpty()) return false;
        String title = normalizeForTitleCard(safe(track.title));
        String artist = normalizeForTitleCard(safe(track.artist));
        if (title.isEmpty() || artist.isEmpty()) return false;
        return norm.startsWith(title + artist) || norm.startsWith(artist + title);
    }

    private static String normalizeForTitleCard(String value) {
        return value.toLowerCase(java.util.Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", "");
    }

    private static void addCreditNames(java.util.Set<String> names, String raw) {
        if (isBlank(raw)) return;
        for (String name : raw.split("[/、,，]")) {
            String trimmed = name.trim();
            if (!trimmed.isEmpty()) names.add(trimmed);
        }
    }

    private void applyNeteaseTranslation(LyricsDocument doc, String translatedLrc) {
        java.util.Map<Long, String> byTimestamp = new java.util.LinkedHashMap<>();
        for (String rawLine : translatedLrc.split("\\r?\\n")) {
            Matcher matcher = LRC_TIMESTAMP.matcher(cleanInvisibles(rawLine));
            if (!matcher.matches()) continue;
            String text = cleanInvisibles(matcher.group(4));
            if (isBlank(text)) continue;
            byTimestamp.put(lrcTimestampMs(matcher), text);
        }
        if (byTimestamp.isEmpty()) return;
        for (LyricsLine line : doc.lines) {
            String translated = byTimestamp.get(line.startMs);
            if (translated != null && !translated.equals(line.text)) {
                line.providerTranslatedText = translated;
            }
        }
    }

    private static long lrcTimestampMs(Matcher matcher) {
        long minutes = parseLongSafe(matcher.group(1));
        long seconds = parseLongSafe(matcher.group(2));
        String fraction = matcher.group(3);
        long millis = 0;
        if (fraction != null && !fraction.isEmpty()) {
            millis = parseLongSafe((fraction + "000").substring(0, 3));
        }
        return minutes * 60000 + seconds * 1000 + millis;
    }
}
