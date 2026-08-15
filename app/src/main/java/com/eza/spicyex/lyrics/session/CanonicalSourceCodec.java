package com.eza.spicyex.lyrics.session;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.eza.spicyex.lyrics.BackgroundLine;
import com.eza.spicyex.lyrics.Json;
import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.lyrics.LyricsLine;
import com.eza.spicyex.lyrics.SyllableSegment;

/**
 * Serializes the canonical projection of a parsed document: original text, timing, spans, and
 * source provenance.
 *
 * <p>Generated reading and translation text is never written, so a restored base can never carry
 * stale derived state. Provider-supplied translations are canonical source data and are kept in
 * their provider fields; turning them into displayed text stays the Meaning layer's job.
 */
public final class CanonicalSourceCodec {
    /** Bump only when this record's shape changes. Independent of any derived-layer contract. */
    public static final int SCHEMA_VERSION = 1;

    private CanonicalSourceCodec() {
    }

    public static String encode(LyricsDocument document, int sourceRevision, String canonicalDigest,
                                long savedAtMs) {
        if (document == null) return "";
        JsonObject root = new JsonObject();
        root.addProperty("schema", SCHEMA_VERSION);
        root.addProperty("sourceRevision", sourceRevision);
        root.addProperty("canonicalDigest", nz(canonicalDigest));
        root.addProperty("savedAtMs", savedAtMs);
        root.addProperty("trackId", nz(document.trackId));
        root.addProperty("provider", nz(document.provider));
        root.addProperty("songWriters", nz(document.songWriters));
        root.addProperty("type", nz(document.type));
        root.addProperty("language", nz(document.language));
        root.addProperty("fetchSource", nz(document.fetchSource));
        root.addProperty("spicyFormat", nz(document.spicyFormat));
        root.addProperty("durationMs", document.durationMs);
        root.addProperty("startTimeMs", document.startTimeMs);
        JsonArray lines = new JsonArray();
        for (LyricsLine line : document.lines) {
            if (line == null) continue;
            JsonObject item = new JsonObject();
            item.addProperty("text", nz(line.text));
            item.addProperty("startMs", line.startMs);
            item.addProperty("endMs", line.endMs);
            if (line.interlude) item.addProperty("interlude", true);
            if (line.oppositeAligned) item.addProperty("oppositeAligned", true);
            if (!nz(line.providerTranslatedText).isEmpty()) {
                item.addProperty("providerTranslatedText", line.providerTranslatedText);
                item.addProperty("providerTranslationLanguage", nz(line.providerTranslationLanguage));
            }
            item.add("syllables", encodeSyllables(line.syllables));
            JsonArray backgrounds = new JsonArray();
            for (BackgroundLine background : line.backgroundLines) {
                if (background == null) continue;
                JsonObject bg = new JsonObject();
                bg.addProperty("text", nz(background.text));
                bg.addProperty("startMs", background.startMs);
                bg.addProperty("endMs", background.endMs);
                if (!nz(background.providerTranslatedText).isEmpty()) {
                    bg.addProperty("providerTranslatedText", background.providerTranslatedText);
                    bg.addProperty("providerTranslationLanguage", nz(background.providerTranslationLanguage));
                }
                bg.add("syllables", encodeSyllables(background.syllables));
                backgrounds.add(bg);
            }
            item.add("backgroundLines", backgrounds);
            lines.add(item);
        }
        root.add("lines", lines);
        return root.toString();
    }

    /** @return the restored canonical document, or null when the record is absent or incompatible */
    public static Record decode(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        try {
            JsonElement parsed = JsonParser.parseString(raw);
            if (!parsed.isJsonObject()) return null;
            JsonObject root = parsed.getAsJsonObject();
            if ((int) Json.optDouble(root, -1, "schema") != SCHEMA_VERSION) return null;
            LyricsDocument document = new LyricsDocument();
            document.trackId = Json.optString(root, "trackId");
            document.provider = Json.optString(root, "provider");
            document.songWriters = Json.optString(root, "songWriters");
            document.type = Json.optString(root, "type");
            document.language = Json.optString(root, "language");
            document.fetchSource = Json.optString(root, "fetchSource");
            document.spicyFormat = Json.optString(root, "spicyFormat");
            document.durationMs = (long) Json.optDouble(root, 0, "durationMs");
            document.startTimeMs = (long) Json.optDouble(root, 0, "startTimeMs");
            JsonArray lines = Json.optArray(root, "lines");
            if (lines == null) return null;
            for (JsonElement element : lines) {
                if (!element.isJsonObject()) continue;
                JsonObject item = element.getAsJsonObject();
                LyricsLine line = new LyricsLine();
                line.text = Json.optString(item, "text");
                line.startMs = (long) Json.optDouble(item, 0, "startMs");
                line.endMs = (long) Json.optDouble(item, 0, "endMs");
                line.interlude = Json.optBoolean(item, false, "interlude");
                line.oppositeAligned = Json.optBoolean(item, false, "oppositeAligned");
                line.providerTranslatedText = Json.optString(item, "providerTranslatedText");
                line.providerTranslationLanguage = Json.optString(item, "providerTranslationLanguage");
                decodeSyllables(Json.optArray(item, "syllables"), line.syllables);
                JsonArray backgrounds = Json.optArray(item, "backgroundLines");
                if (backgrounds != null) {
                    for (JsonElement bgElement : backgrounds) {
                        if (!bgElement.isJsonObject()) continue;
                        JsonObject bgItem = bgElement.getAsJsonObject();
                        BackgroundLine background = new BackgroundLine();
                        background.text = Json.optString(bgItem, "text");
                        background.startMs = (long) Json.optDouble(bgItem, 0, "startMs");
                        background.endMs = (long) Json.optDouble(bgItem, 0, "endMs");
                        background.providerTranslatedText = Json.optString(bgItem, "providerTranslatedText");
                        background.providerTranslationLanguage =
                                Json.optString(bgItem, "providerTranslationLanguage");
                        decodeSyllables(Json.optArray(bgItem, "syllables"), background.syllables);
                        line.backgroundLines.add(background);
                    }
                }
                document.lines.add(line);
            }
            if (document.lines.isEmpty()) return null;
            return new Record(document, (int) Json.optDouble(root, 1, "sourceRevision"),
                    Json.optString(root, "canonicalDigest"), (long) Json.optDouble(root, 0, "savedAtMs"));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static JsonArray encodeSyllables(java.util.List<SyllableSegment> syllables) {
        JsonArray out = new JsonArray();
        if (syllables == null) return out;
        for (SyllableSegment segment : syllables) {
            if (segment == null) continue;
            JsonObject item = new JsonObject();
            item.addProperty("spanId", nz(segment.spanId));
            item.addProperty("text", nz(segment.text));
            item.addProperty("sourceText", nz(segment.sourceText));
            item.addProperty("startMs", segment.startMs);
            item.addProperty("endMs", segment.endMs);
            item.addProperty("totalMs", segment.totalMs);
            if (segment.providerPartOfWord != null) {
                item.addProperty("providerPartOfWord", segment.providerPartOfWord);
            }
            if (segment.boundaryAfter) item.addProperty("boundaryAfter", true);
            item.addProperty("boundaryProvenance", nz(segment.boundaryProvenance));
            item.addProperty("canonicalStartCp", segment.canonicalStartCp);
            item.addProperty("canonicalEndCp", segment.canonicalEndCp);
            if (segment.partOfWord) item.addProperty("partOfWord", true);
            if (segment.dot) item.addProperty("dot", true);
            if (segment.bgWord) item.addProperty("bgWord", true);
            out.add(item);
        }
        return out;
    }

    private static void decodeSyllables(JsonArray source, java.util.List<SyllableSegment> target) {
        if (source == null) return;
        for (JsonElement element : source) {
            if (!element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            SyllableSegment segment = new SyllableSegment();
            segment.spanId = Json.optString(item, "spanId");
            segment.text = Json.optString(item, "text");
            segment.sourceText = Json.optString(item, "sourceText");
            segment.startMs = (long) Json.optDouble(item, 0, "startMs");
            segment.endMs = (long) Json.optDouble(item, 0, "endMs");
            segment.totalMs = (long) Json.optDouble(item, 0, "totalMs");
            segment.providerPartOfWord = item.has("providerPartOfWord")
                    ? Boolean.valueOf(Json.optBoolean(item, false, "providerPartOfWord")) : null;
            segment.boundaryAfter = Json.optBoolean(item, false, "boundaryAfter");
            segment.boundaryProvenance = Json.optString(item, "boundaryProvenance");
            segment.canonicalStartCp = (int) Json.optDouble(item, -1, "canonicalStartCp");
            segment.canonicalEndCp = (int) Json.optDouble(item, -1, "canonicalEndCp");
            segment.partOfWord = Json.optBoolean(item, false, "partOfWord");
            segment.dot = Json.optBoolean(item, false, "dot");
            segment.bgWord = Json.optBoolean(item, false, "bgWord");
            target.add(segment);
        }
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }

    /** A restored canonical source: the document plus the identity it was stored under. */
    public static final class Record {
        public final LyricsDocument document;
        public final int sourceRevision;
        public final String canonicalDigest;
        public final long savedAtMs;

        Record(LyricsDocument document, int sourceRevision, String canonicalDigest, long savedAtMs) {
            this.document = document;
            this.sourceRevision = Math.max(1, sourceRevision);
            this.canonicalDigest = nz(canonicalDigest);
            this.savedAtMs = savedAtMs;
        }
    }
}
