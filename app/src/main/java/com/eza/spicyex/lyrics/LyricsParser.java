package com.eza.spicyex.lyrics;

import android.app.Activity;
import android.content.Context;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.eza.spicyex.SpotifyTrack;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.robv.android.xposed.XposedBridge;
import static com.eza.spicyex.lyrics.LyricUtils.isBlank;
import static com.eza.spicyex.lyrics.LyricUtils.safe;
import static com.eza.spicyex.lyrics.LyricUtils.trackIdFromUri;

/** Source adapters for Spicy API and LRCLIB lyric payloads. */
public final class LyricsParser implements LyricsRepository.Parser {
    private static final String TAG = "[SpotifyPlusSpicyParser]";
    private static final Pattern LRC_TIMESTAMP = Pattern.compile("^\\[(\\d{1,3}):(\\d{2})(?:\\.(\\d{1,3}))?\\]\\s*(.*)$");

    private final Finalizer finalizer;

    public LyricsParser(Finalizer finalizer) {
        this.finalizer = finalizer;
    }

    @Override
    public LyricsDocument parseSpicyLyrics(Activity activity, SpotifyTrack track, String raw, boolean fromCache) {
        XposedBridge.log(TAG + " parseSpicyLyrics track=" + (track == null ? "null" : safe(track.uri))
                + " fromCache=" + fromCache + " bytes=" + (raw == null ? 0 : raw.length()));
        JsonElement root = unpackSpicyPayloads(JsonParser.parseString(raw));
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
        doc.fetchSource = fromCache ? "spicy_api_cache" : "spicy_api";
        doc.provider = providerLabelFromSource(firstNonBlank(
                Json.optString(data, "source", "Source", "Provider", "provider"),
                Json.findFirstString(root, "source", "Source", "Provider", "provider")
        ));
        doc.songWriters = joinSongWriters(Json.optArray(data, "SongWriters", "songWriters", "Writers"));
        JsonArray selectedLines = Json.optArray(data, "Lines", "lines", "Content", "content");
        XposedBridge.log(TAG + " spicy parse selected type=" + type + " candidateLines=" + (selectedLines == null ? 0 : selectedLines.size()));

        if ("Static".equalsIgnoreCase(type)) {
            parseStatic(data, doc);
        } else if ("Line".equalsIgnoreCase(type)) {
            parseLine(data, doc);
        } else if ("Syllable".equalsIgnoreCase(type)) {
            parseSyllable(data, doc);
        } else {
            throw new IllegalStateException("unsupported lyrics type " + type);
        }

        finalizeParsedDocument(activity, doc);
        return doc;
    }

    @Override
    public LyricsDocument parseLrclibLyrics(Activity activity, SpotifyTrack track, String body) {
        JsonElement root = JsonParser.parseString(body);
        JsonObject best = null;
        if (root.isJsonArray()) {
            JsonArray array = root.getAsJsonArray();
            for (JsonElement element : array) {
                if (!element.isJsonObject()) continue;
                JsonObject candidate = element.getAsJsonObject();
                if (best == null) best = candidate;
                if (!isBlank(Json.optString(candidate, "syncedLyrics"))) {
                    best = candidate;
                    break;
                }
            }
        } else if (root.isJsonObject()) {
            best = root.getAsJsonObject();
        }
        if (best == null) throw new IllegalStateException("no LRCLIB result");

        LyricsDocument doc = new LyricsDocument();
        doc.trackId = trackIdFromUri(track == null ? "" : track.uri);
        doc.durationMs = track == null ? 0 : Math.max(0, track.duration);
        doc.fetchSource = "lrclib";
        doc.provider = "LRCLIB";
        doc.language = "";

        String synced = Json.optString(best, "syncedLyrics");
        if (!isBlank(synced)) {
            doc.type = "Line";
            parseLrcLines(synced, doc);
        } else {
            doc.type = "Static";
            parsePlainLines(Json.optString(best, "plainLyrics"), doc);
        }
        finalizeParsedDocument(activity, doc);
        return doc;
    }

    private void parseLrcLines(String synced, LyricsDocument doc) {
        String[] rawLines = synced.split("\\r?\\n");
        for (String rawLine : rawLines) {
            Matcher matcher = LRC_TIMESTAMP.matcher(rawLine.trim());
            if (!matcher.matches()) continue;
            long minutes = parseLongSafe(matcher.group(1));
            long seconds = parseLongSafe(matcher.group(2));
            String fraction = matcher.group(3);
            long millis = 0;
            if (fraction != null && !fraction.isEmpty()) {
                String ms = (fraction + "000").substring(0, 3);
                millis = parseLongSafe(ms);
            }
            String text = safe(matcher.group(4)).trim();
            LyricsLine line = new LyricsLine();
            line.startMs = minutes * 60000 + seconds * 1000 + millis;
            if (doc.startTimeMs <= 0 && line.startMs > 0) doc.startTimeMs = line.startMs;
            line.text = isBlank(text) ? "♪" : text;
            line.interlude = isBlank(text);
            applySecondaryText(line, new JsonObject());
            doc.lines.add(line);
        }
    }

    private void parsePlainLines(String plain, LyricsDocument doc) {
        if (isBlank(plain)) return;
        long cursor = 0;
        for (String rawLine : plain.split("\\r?\\n")) {
            String text = rawLine.trim();
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
            String text = Json.optString(object, "Text", "text");
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
                String text = Json.optString(object, "Text", "text");
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
            String text = joinSyllables(syllables, "Text", "text");
            if (isBlank(text)) continue;

            LyricsLine line = new LyricsLine();
            line.text = text;
            line.startMs = secondsToMs(Json.optDouble(lead, Json.optDouble(object, 0d, "StartTime", "startTime"), "StartTime", "startTime"));
            line.endMs = secondsToMs(Json.optDouble(lead, Json.optDouble(object, 0d, "EndTime", "endTime"), "EndTime", "endTime"));
            line.oppositeAligned = Json.optBoolean(object, false, "OppositeAligned", "oppositeAligned");
            line.syllables = parseSyllableSegments(syllables, line.startMs, line.endMs);

            String transliterated = joinSyllables(syllables, "TransliteratedText", "transliteratedText", "RomanizedText", "romanizedText");
            if (!isBlank(transliterated) && !transliterated.equals(text)) line.romanizedText = transliterated;
            String translated = joinSyllables(syllables, "TranslatedText", "translatedText", "Translation", "translation");
            if (!isBlank(translated) && !translated.equals(text)) line.translatedText = translated;
            line.backgroundLines = parseBackgroundLines(object);
            applySecondaryText(line, object);
            doc.lines.add(line);
        }
    }

    private void applySecondaryText(LyricsLine line, JsonObject object) {
        String providerRomanized = firstNonBlank(
                Json.optString(object, "TransliteratedText", "transliteratedText"),
                Json.optString(object, "RomanizedText", "romanizedText", "RomanisedText", "romanisedText")
        );
        if (!isBlank(providerRomanized) && !providerRomanized.equals(line.text) && !SpicyTextDetection.hasRomanizableScript(providerRomanized)) {
            line.romanizedText = providerRomanized;
        }

        String translated = Json.optString(object, "TranslatedText", "translatedText", "Translation", "translation");
        if (!isBlank(translated) && !translated.equals(line.text)) {
            line.translatedText = translated;
        }

        line.japaneseReading = parseJapaneseReading(object);
    }

    public static SpicyJapaneseChineseProcessor.JapaneseReading parseJapaneseReading(JsonObject object) {
        JsonObject reading = Json.optObject(object, "JapaneseReading", "japaneseReading");
        if (reading == null) return null;
        String sourceText = Json.optString(reading, "sourceText", "SourceText");
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
        if (isBlank(sourceText) && isBlank(romaji) && furigana.isEmpty()) return null;
        return new SpicyJapaneseChineseProcessor.JapaneseReading(sourceText, romaji, furigana);
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
            line.syllables = parseSyllableSegments(syllables, line.startMs, line.endMs);
            line.text = joinSyllables(syllables, "Text", "text");
            line.romanizedText = firstNonBlank(
                    joinSyllables(syllables, "TransliteratedText", "transliteratedText"),
                    joinSyllables(syllables, "RomanizedText", "romanizedText")
            );
            line.translatedText = joinSyllables(syllables, "TranslatedText", "translatedText", "Translation", "translation");
            out.add(line);
        }
        return out;
    }

    private static List<SyllableSegment> parseSyllableSegments(JsonArray syllables, long lineStartMs, long lineEndMs) {
        ArrayList<SyllableSegment> out = new ArrayList<>();
        if (syllables == null || syllables.isEmpty()) return out;
        long fallbackDuration = Math.max(1, lineEndMs - lineStartMs);
        long fallbackStep = Math.max(1, fallbackDuration / Math.max(1, syllables.size()));
        for (int i = 0; i < syllables.size(); i++) {
            JsonElement element = syllables.get(i);
            if (!element.isJsonObject()) continue;
            JsonObject syllable = element.getAsJsonObject();
            String text = Json.optString(syllable, "Text", "text");
            if (isBlank(text)) continue;
            SyllableSegment seg = new SyllableSegment();
            seg.text = text;
            seg.partOfWord = Json.optBoolean(syllable, false, "IsPartOfWord", "isPartOfWord");
            long fallbackStart = lineStartMs + fallbackStep * i;
            long fallbackEnd = i == syllables.size() - 1 ? lineEndMs : fallbackStart + fallbackStep;
            seg.startMs = secondsToMs(Json.optDouble(syllable, fallbackStart / 1000d, "StartTime", "startTime"));
            seg.endMs = secondsToMs(Json.optDouble(syllable, fallbackEnd / 1000d, "EndTime", "endTime"));
            if (seg.endMs <= seg.startMs) seg.endMs = Math.min(lineEndMs, seg.startMs + fallbackStep);
            if (seg.endMs <= seg.startMs) seg.endMs = seg.startMs + 1;
            seg.totalMs = Math.max(0, seg.endMs - seg.startMs);
            out.add(seg);
        }
        return out;
    }

    private static String joinSyllables(JsonArray syllables, String... textKeys) {
        if (syllables == null) return "";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < syllables.size(); i++) {
            JsonElement element = syllables.get(i);
            if (!element.isJsonObject()) continue;
            JsonObject syllable = element.getAsJsonObject();
            String text = Json.optString(syllable, textKeys);
            if (isBlank(text)) continue;
            boolean isPart = Json.optBoolean(syllable, false, "IsPartOfWord", "isPartOfWord");
            if (out.length() > 0 && !isPart) out.append(' ');
            out.append(text);
        }
        return out.toString().trim();
    }

    private static JsonElement unpackSpicyPayloads(JsonElement element) {
        if (element == null || element.isJsonNull()) return element;
        if (SpicyObjPack.isPackedPayload(element)) {
            JsonElement unpacked = SpicyObjPack.unpack(element);
            XposedBridge.log(TAG + " unpacked SLObjPack Spicy payload");
            return unpacked;
        }
        if (element.isJsonArray()) {
            JsonArray out = new JsonArray();
            for (JsonElement child : element.getAsJsonArray()) out.add(unpackSpicyPayloads(child));
            return out;
        }
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            JsonObject out = new JsonObject();
            for (String key : object.keySet()) out.add(key, unpackSpicyPayloads(object.get(key)));
            return out;
        }
        return element;
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

    private static String providerLabelFromSource(String source) {
        String value = safe(source).trim();
        if (value.isEmpty()) return "Unknown";
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
}
