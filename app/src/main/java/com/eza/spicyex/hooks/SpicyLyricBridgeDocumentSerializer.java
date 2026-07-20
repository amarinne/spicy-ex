package com.eza.spicyex.hooks;

import com.eza.spicyex.lyrics.AppliedLine;
import com.eza.spicyex.lyrics.DisplayLayoutGroup;
import com.eza.spicyex.lyrics.LyricTimeline;
import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.lyrics.SyllableSegment;
import com.eza.spicyex.lyrics.SpicyJapaneseChineseProcessor;
import com.eza.spicyex.lyrics.reading.CodePointRanges;
import com.eza.spicyex.lyrics.reading.ReadingModels.CanonicalSpanMapping;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

final class SpicyLyricBridgeDocumentSerializer {
    static final int DOCUMENT_VERSION = 1;
    static final int MAX_COMPRESSED_BYTES = 1024 * 1024;
    static final int MAX_UNCOMPRESSED_BYTES = 4 * 1024 * 1024;
    static final int MAX_ROWS = 5_000;
    static final int MAX_WORDS = 20_000;
    private static final int MAX_TEXT = 8_192;

    private SpicyLyricBridgeDocumentSerializer() {
    }

    static byte[] serialize(
            LyricsDocument document,
            String producerId,
            int generation,
            String trackUri
    ) throws IOException {
        if (document == null) throw new IOException("missing document");
        JsonObject root = new JsonObject();
        root.addProperty("version", DOCUMENT_VERSION);
        root.addProperty("producerId", bounded(producerId));
        root.addProperty("generation", generation);
        root.addProperty("trackUri", bounded(trackUri));
        root.addProperty("provider", bounded(document.provider));
        root.addProperty("language", bounded(document.language));
        root.addProperty("type", bounded(document.type));
        root.addProperty("durationMs", Math.max(0L, document.durationMs));
        root.addProperty("processingVersion", document.processingVersion);

        JsonArray rows = new JsonArray();
        int wordCount = 0;
        int rowCount = Math.min(document.appliedLines.size(), MAX_ROWS);
        for (int i = 0; i < rowCount; i++) {
            AppliedLine row = document.appliedLines.get(i);
            if (row == null) continue;
            long encodedStartMs = Math.max(0L, row.startMs);
            JsonObject encoded = new JsonObject();
            encoded.addProperty("role", row.dotLine ? "INTERLUDE" : row.bgLine ? "BACKGROUND" : "LEAD");
            encoded.addProperty("startMs", encodedStartMs);
            encoded.addProperty("endMs", Math.max(encodedStartMs, row.endMs));
            encoded.addProperty("fillEndMs", Math.max(encodedStartMs, LyricTimeline.fillEndMs(row)));
            encoded.addProperty("alignedRight", row.oppositeAligned);
            encoded.addProperty("text", bounded(row.text));
            encoded.addProperty("romanized", bounded(readingText(row)));
            encoded.addProperty("translated", bounded(row.translatedText));
            JsonArray furigana = new JsonArray();
            if (row.japaneseReading != null && row.japaneseReading.furigana != null) {
                for (SpicyJapaneseChineseProcessor.FuriganaSegment segment : row.japaneseReading.furigana) {
                    if (segment == null || segment.reading == null || segment.reading.trim().isEmpty()) continue;
                    JsonObject ruby = new JsonObject();
                    ruby.addProperty("start", Math.max(0, segment.start));
                    ruby.addProperty("end", Math.max(segment.start, segment.end));
                    ruby.addProperty("reading", bounded(segment.reading));
                    furigana.add(ruby);
                }
            }
            encoded.add("furigana", furigana);

            JsonArray layoutGroups = new JsonArray();
            for (DisplayLayoutGroup group : DisplayLayoutGroup.forLine(
                    document.language, row.text, row.japaneseReading)) {
                if (group == null || group.end <= group.start) continue;
                JsonObject layout = new JsonObject();
                layout.addProperty("start", group.start);
                layout.addProperty("end", group.end);
                layout.addProperty("kind", bounded(group.kind));
                layout.addProperty("keepTogether", group.keepTogether);
                layout.addProperty("confidence", group.confidence);
                layoutGroups.add(layout);
            }
            encoded.add("layoutGroups", layoutGroups);

            JsonArray words = new JsonArray();
            int sourceCursor = 0;
            for (int wordIndex = 0; wordIndex < row.words.size(); wordIndex++) {
                SyllableSegment word = row.words.get(wordIndex);
                if (word == null || wordCount >= MAX_WORDS) break;
                long encodedWordStartMs = Math.max(0L, word.startMs);
                int[] sourceRange = wordSourceRange(row, word, wordIndex, sourceCursor);
                JsonObject encodedWord = new JsonObject();
                encodedWord.addProperty("text", bounded(word.text));
                encodedWord.addProperty("romanized", bounded(word.romanizedText));
                encodedWord.addProperty("startMs", encodedWordStartMs);
                encodedWord.addProperty("endMs", Math.max(encodedWordStartMs, word.endMs));
                encodedWord.addProperty("partOfWord", word.partOfWord);
                encodedWord.addProperty("sourceStart", sourceRange[0]);
                encodedWord.addProperty("sourceEnd", sourceRange[1]);
                words.add(encodedWord);
                if (sourceRange[1] >= 0) sourceCursor = Math.max(sourceCursor, sourceRange[1]);
                wordCount++;
            }
            encoded.add("words", words);
            rows.add(encoded);
        }
        root.add("rows", rows);

        byte[] raw = root.toString().getBytes(StandardCharsets.UTF_8);
        if (raw.length > MAX_UNCOMPRESSED_BYTES) throw new IOException("document too large");
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(raw.length, 64 * 1024));
        try (GZIPOutputStream gzip = new GZIPOutputStream(output)) {
            gzip.write(raw);
        }
        byte[] compressed = output.toByteArray();
        if (compressed.length > MAX_COMPRESSED_BYTES) throw new IOException("document too large");
        return compressed;
    }

    private static String bounded(String value) {
        if (value == null) return "";
        return value.length() <= MAX_TEXT ? value : value.substring(0, MAX_TEXT);
    }

    private static int[] wordSourceRange(
            AppliedLine row,
            SyllableSegment word,
            int fallbackIndex,
            int fallbackOffset
    ) {
        String text = row == null || row.text == null ? "" : row.text;
        if (row != null && row.readingRenderPlan != null && word != null) {
            String spanId = word.spanId == null || word.spanId.trim().isEmpty()
                    ? String.valueOf(fallbackIndex) : word.spanId;
            int startCp = Integer.MAX_VALUE;
            int endCp = -1;
            for (String id : spanId.split("\\+")) {
                for (CanonicalSpanMapping mapping : row.readingRenderPlan.sourceUnits) {
                    if (mapping == null || mapping.canonicalRange == null || !id.equals(mapping.spanId)) continue;
                    startCp = Math.min(startCp, mapping.canonicalRange.startCp);
                    endCp = Math.max(endCp, mapping.canonicalRange.endCp);
                }
            }
            if (startCp >= 0 && endCp > startCp && endCp <= CodePointRanges.length(text)) {
                int start = CodePointRanges.codePointOffsetToUtf16Index(text, startCp);
                int end = CodePointRanges.codePointOffsetToUtf16Index(text, endCp);
                if (start < end && end <= text.length()) return new int[]{start, end};
            }
        }
        String value = word == null || word.text == null ? "" : word.text;
        int cursor = Math.max(0, Math.min(text.length(), fallbackOffset));
        while (cursor < text.length() && Character.isWhitespace(text.charAt(cursor))) cursor++;
        int found = value.isEmpty() ? -1 : text.indexOf(value, cursor);
        if (found < 0 || found + value.length() > text.length()) return new int[]{-1, -1};
        return new int[]{found, found + value.length()};
    }

    private static String readingText(AppliedLine row) {
        if (row == null) return "";
        String planned = row.readingRenderPlan == null ? "" : row.readingRenderPlan.joinedDisplayText;
        return planned == null || planned.trim().isEmpty() ? row.romanizedText : planned;
    }
}
