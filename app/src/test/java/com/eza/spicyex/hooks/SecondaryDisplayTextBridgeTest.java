package com.eza.spicyex.hooks;

import static org.junit.Assert.assertEquals;

import com.eza.spicyex.lyrics.AppliedLine;
import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.lyrics.SyllableSegment;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;

public class SecondaryDisplayTextBridgeTest {
    @Test
    public void serializerDedupesCaseAndPunctuationVariantsAgainstMainLine() throws Exception {
        LyricsDocument document = new LyricsDocument();
        document.type = "Syllable";
        document.durationMs = 60_000L;

        AppliedLine dupRow = new AppliedLine();
        dupRow.text = "Hello, world!";
        dupRow.romanizedText = "hello world";
        dupRow.translatedText = "HELLO WORLD...";
        dupRow.startMs = 1_000L;
        dupRow.endMs = 3_000L;
        SyllableSegment w1 = new SyllableSegment();
        w1.text = "Hello,";
        w1.romanizedText = "hello";
        w1.startMs = 1_000L;
        w1.endMs = 2_000L;
        dupRow.words.add(w1);
        document.appliedLines.add(dupRow);

        AppliedLine distinctRow = new AppliedLine();
        distinctRow.text = "ありがとう";
        distinctRow.romanizedText = "arigatou";
        distinctRow.translatedText = "Thank you";
        distinctRow.startMs = 3_000L;
        distinctRow.endMs = 5_000L;
        document.appliedLines.add(distinctRow);

        byte[] compressed = SpicyLyricBridgeDocumentSerializer.serialize(
                document, "producer", 1, "spotify:track:dedup");
        JsonObject root = JsonParser.parseString(unzip(compressed)).getAsJsonObject();

        JsonObject encodedDup = root.getAsJsonArray("rows").get(0).getAsJsonObject();
        assertEquals("Hello, world!", encodedDup.get("text").getAsString());
        assertEquals("", encodedDup.get("romanized").getAsString());
        assertEquals("", encodedDup.get("translated").getAsString());
        assertEquals("", encodedDup.getAsJsonArray("words").get(0).getAsJsonObject()
                .get("romanized").getAsString());

        JsonObject encodedDistinct = root.getAsJsonArray("rows").get(1).getAsJsonObject();
        assertEquals("arigatou", encodedDistinct.get("romanized").getAsString());
        assertEquals("Thank you", encodedDistinct.get("translated").getAsString());
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
