package com.eza.spicyex.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.eza.spicyex.lyrics.AppliedLine;
import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.lyrics.LyricsLine;
import com.eza.spicyex.lyrics.SyllableSegment;
import com.eza.spicyex.lyrics.reading.ReadingModels.CanonicalSpanMapping;
import com.eza.spicyex.lyrics.reading.ReadingModels.RenderPlan;
import com.eza.spicyex.lyrics.reading.ReadingModels.TextRange;
import com.eza.spicyex.lyrics.reading.ReadingPlanFactory;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.zip.GZIPInputStream;

public class SpicyLyricBridgeDocumentSerializerTest {
    @Test
    public void chineseRowInJapaneseDocumentHasNoJapaneseRubyOrLayout() throws Exception {
        LyricsDocument document = new LyricsDocument(); document.language = "ja";
        AppliedLine row = new AppliedLine(); row.text = "中国"; row.endMs = 1000;
        row.sourceLine = new LyricsLine(); row.sourceLine.text = row.text;
        row.sourceLine.detection = com.eza.spicyex.lyrics.session.DetectionResult.detected("", row.text,
                com.eza.spicyex.lyrics.ScriptClassifier.ScriptClass.CHINESE, "zh", .99);
        row.japaneseReading = new com.eza.spicyex.lyrics.SpicyJapaneseChineseProcessor.JapaneseReading(
                "中国", "chuugoku", Collections.singletonList(
                new com.eza.spicyex.lyrics.SpicyJapaneseChineseProcessor.FuriganaSegment(0, 2, "ちゅうごく")));
        document.appliedLines.add(row);
        JsonObject encoded = JsonParser.parseString(unzip(SpicyLyricBridgeDocumentSerializer.serialize(
                document, "test", 1, "track"))).getAsJsonObject()
                .getAsJsonArray("rows").get(0).getAsJsonObject();
        assertEquals(0, encoded.getAsJsonArray("furigana").size());
        for (com.google.gson.JsonElement group : encoded.getAsJsonArray("layoutGroups")) {
            org.junit.Assert.assertFalse(group.getAsJsonObject().get("kind").getAsString().startsWith("ja"));
        }
    }

    @Test
    public void serializesNormalizedRowsAndWords() throws Exception {
        LyricsDocument document = new LyricsDocument();
        document.provider = "test";
        document.language = "ko";
        document.type = "Syllable";
        document.durationMs = 120_000L;
        AppliedLine row = new AppliedLine();
        row.text = "original";
        row.romanizedText = "romanized";
        row.translatedText = "translated";
        row.startMs = 1_000L;
        row.endMs = 3_000L;
        SyllableSegment word = new SyllableSegment();
        word.text = "word";
        word.startMs = 1_000L;
        word.endMs = 1_500L;
        row.words.add(word);
        document.appliedLines.add(row);

        byte[] compressed = SpicyLyricBridgeDocumentSerializer.serialize(
                document, "producer", 4, "spotify:track:test");
        JsonObject json = JsonParser.parseString(unzip(compressed)).getAsJsonObject();

        assertEquals(2, json.get("version").getAsInt());
        assertEquals("spotify:track:test", json.get("trackUri").getAsString());
        assertEquals("LEAD", json.getAsJsonArray("rows").get(0).getAsJsonObject()
                .get("role").getAsString());
        assertEquals("word", json.getAsJsonArray("rows").get(0).getAsJsonObject()
                .getAsJsonArray("words").get(0).getAsJsonObject().get("text").getAsString());
        assertTrue(!json.getAsJsonArray("rows").get(0).getAsJsonObject()
                .getAsJsonArray("words").get(0).getAsJsonObject().get("boundaryAfter").getAsBoolean());
        assertEquals(1, json.getAsJsonArray("rows").get(0).getAsJsonObject()
                .getAsJsonArray("layoutGroups").size());
        assertTrue(compressed.length < SpicyLyricBridgeDocumentSerializer.MAX_COMPRESSED_BYTES);
    }

    @Test
    public void repeatedWordsSerializeDistinctCanonicalSourceRanges() throws Exception {
        LyricsDocument document = new LyricsDocument();
        AppliedLine row = new AppliedLine();
        row.text = "重複 重複";
        row.startMs = 1_000L;
        row.endMs = 3_000L;
        row.readingRenderPlan = new RenderPlan(
                "line", Arrays.asList(
                        new CanonicalSpanMapping("first", new TextRange(0, 2)),
                        new CanonicalSpanMapping("second", new TextRange(3, 5))),
                Collections.emptyList(), Collections.emptyList(), "", "");
        row.words.add(word("first", "重複"));
        row.words.add(word("second", "重複"));
        document.appliedLines.add(row);

        JsonObject encoded = JsonParser.parseString(unzip(SpicyLyricBridgeDocumentSerializer.serialize(
                document, "producer", 4, "spotify:track:test"))).getAsJsonObject()
                .getAsJsonArray("rows").get(0).getAsJsonObject();

        assertEquals(0, encoded.getAsJsonArray("words").get(0).getAsJsonObject()
                .get("sourceStart").getAsInt());
        assertEquals(2, encoded.getAsJsonArray("words").get(0).getAsJsonObject()
                .get("sourceEnd").getAsInt());
        assertEquals(3, encoded.getAsJsonArray("words").get(1).getAsJsonObject()
                .get("sourceStart").getAsInt());
        assertEquals(5, encoded.getAsJsonArray("words").get(1).getAsJsonObject()
                .get("sourceEnd").getAsInt());
    }

    @Test
    public void missingCanonicalMappingUsesSequentialFallbackOrInvalidSentinel() throws Exception {
        LyricsDocument document = new LyricsDocument();
        AppliedLine row = new AppliedLine();
        row.text = "重複 重複";
        row.words.add(word("", "重複"));
        row.words.add(word("", "見つからない"));
        document.appliedLines.add(row);

        JsonObject words = JsonParser.parseString(unzip(SpicyLyricBridgeDocumentSerializer.serialize(
                document, "producer", 4, "spotify:track:test"))).getAsJsonObject()
                .getAsJsonArray("rows").get(0).getAsJsonObject();

        assertEquals(0, words.getAsJsonArray("words").get(0).getAsJsonObject()
                .get("sourceStart").getAsInt());
        assertEquals(2, words.getAsJsonArray("words").get(0).getAsJsonObject()
                .get("sourceEnd").getAsInt());
        assertEquals(-1, words.getAsJsonArray("words").get(1).getAsJsonObject()
                .get("sourceStart").getAsInt());
        assertEquals(-1, words.getAsJsonArray("words").get(1).getAsJsonObject()
                .get("sourceEnd").getAsInt());
    }

    @Test
    public void serializesStructuredReadingDisplayText() throws Exception {
        LyricsDocument document = new LyricsDocument();
        AppliedLine row = new AppliedLine();
        row.text = "original";
        row.startMs = 1_000L;
        row.endMs = 3_000L;
        LyricsLine source = new LyricsLine();
        source.text = row.text;
        source.startMs = row.startMs;
        source.endMs = row.endMs;
        row.readingRenderPlan = ReadingPlanFactory.lineFallback(
                source, "structured reading", "local");
        document.appliedLines.add(row);

        byte[] compressed = SpicyLyricBridgeDocumentSerializer.serialize(
                document, "producer", 4, "spotify:track:test");
        JsonObject json = JsonParser.parseString(unzip(compressed)).getAsJsonObject();

        assertEquals("structured reading", json.getAsJsonArray("rows").get(0).getAsJsonObject()
                .get("romanized").getAsString());
    }

    @Test
    public void clampsNegativeIntervalsAgainstEncodedStart() throws Exception {
        LyricsDocument document = new LyricsDocument();
        AppliedLine row = new AppliedLine();
        row.startMs = -20L;
        row.endMs = -10L;
        SyllableSegment word = new SyllableSegment();
        word.startMs = -15L;
        word.endMs = -5L;
        row.words.add(word);
        document.appliedLines.add(row);

        byte[] compressed = SpicyLyricBridgeDocumentSerializer.serialize(
                document, "producer", 4, "spotify:track:test");
        JsonObject encoded = JsonParser.parseString(unzip(compressed)).getAsJsonObject()
                .getAsJsonArray("rows").get(0).getAsJsonObject();

        assertEquals(0L, encoded.get("startMs").getAsLong());
        assertEquals(0L, encoded.get("endMs").getAsLong());
        assertEquals(0L, encoded.get("fillEndMs").getAsLong());
        JsonObject encodedWord = encoded.getAsJsonArray("words").get(0).getAsJsonObject();
        assertEquals(0L, encodedWord.get("startMs").getAsLong());
        assertEquals(0L, encodedWord.get("endMs").getAsLong());
    }

    @Test
    public void clampsRowsAndWordsToTrackDurationAndOmitsTrailingRows() throws Exception {
        LyricsDocument document = new LyricsDocument();
        document.durationMs = 5000;
        AppliedLine row = new AppliedLine();
        row.startMs = 4900;
        row.endMs = 9000;
        row.sourceLine = new LyricsLine();
        row.sourceLine.endMs = 10000;
        SyllableSegment crossing = word("crossing", "last");
        crossing.startMs = 4800;
        crossing.endMs = 7000;
        row.words.add(crossing);
        SyllableSegment beyond = word("beyond", "late");
        beyond.startMs = 6000;
        beyond.endMs = 7000;
        row.words.add(beyond);
        document.appliedLines.add(row);
        for (long start : new long[]{5000, 6000}) {
            AppliedLine trailing = new AppliedLine();
            trailing.startMs = start;
            trailing.endMs = 7000;
            document.appliedLines.add(trailing);
        }
        JsonObject json = JsonParser.parseString(unzip(SpicyLyricBridgeDocumentSerializer.serialize(
                document, "producer", 1, "spotify:track:test"))).getAsJsonObject();
        assertEquals(1, json.getAsJsonArray("rows").size());
        JsonObject encoded = json.getAsJsonArray("rows").get(0).getAsJsonObject();
        assertEquals(4900, encoded.get("startMs").getAsLong());
        assertEquals(5000, encoded.get("endMs").getAsLong());
        assertEquals(5000, encoded.get("fillEndMs").getAsLong());
        assertEquals(1, encoded.getAsJsonArray("words").size());
        JsonObject word = encoded.getAsJsonArray("words").get(0).getAsJsonObject();
        assertEquals(4900, word.get("startMs").getAsLong());
        assertEquals(5000, word.get("endMs").getAsLong());
        assertEquals(9000, row.endMs);
        assertEquals(7000, crossing.endMs);
    }

    private static SyllableSegment word(String spanId, String text) {
        SyllableSegment word = new SyllableSegment();
        word.spanId = spanId;
        word.text = text;
        return word;
    }

    private static String unzip(byte[] compressed) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GZIPInputStream input = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            byte[] buffer = new byte[1024];
            int read;
            while ((read = input.read(buffer)) >= 0) output.write(buffer, 0, read);
        }
        return output.toString(StandardCharsets.UTF_8.name());
    }
}
