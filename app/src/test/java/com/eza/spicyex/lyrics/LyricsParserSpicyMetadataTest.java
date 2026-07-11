package com.eza.spicyex.lyrics;

import com.eza.spicyex.SpotifyTrack;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class LyricsParserSpicyMetadataTest {
    private final LyricsParser parser = new LyricsParser(null);
    private final SpotifyTrack track = new SpotifyTrack(
            "Song", "Artist", "Album", "spotify:track:test", 0, "", 0, null, 180000, false);

    @Test
    public void parseSpicyLyricsCapturesPackedQueryMetadata() {
        JsonObject result = new JsonObject();
        result.addProperty("httpStatus", 200);
        result.addProperty("format", "json");
        result.add("data", packedStaticLyrics());

        LyricsDocument doc = parser.parseSpicyLyrics(null, track, queryResponse(result).toString(), false);

        assertTrue(doc.spicyPackedPayload);
        assertEquals(Integer.valueOf(200), doc.spicyQueryStatus);
        assertEquals("json", doc.spicyFormat);
        assertFalse(doc.spicyPoisoned);
        assertNull(doc.spicyQualityReason);
        assertEquals("Static", doc.type);
        assertEquals(1, doc.lines.size());
        assertEquals("hello", doc.lines.get(0).text);
    }

    @Test
    public void parseSpicyLyricsLeavesUnpackedPayloadFalse() {
        JsonObject result = new JsonObject();
        result.addProperty("httpStatus", 204);
        result.addProperty("format", "plain");
        result.add("data", unpackedStaticLyrics());

        LyricsDocument doc = parser.parseSpicyLyrics(null, track, queryResponse(result).toString(), false);

        assertFalse(doc.spicyPackedPayload);
        assertEquals(Integer.valueOf(204), doc.spicyQueryStatus);
        assertEquals("plain", doc.spicyFormat);
        assertFalse(doc.spicyPoisoned);
        assertNull(doc.spicyQualityReason);
        assertEquals("Static", doc.type);
        assertEquals(1, doc.lines.size());
        assertEquals("hello", doc.lines.get(0).text);
    }

    @Test
    public void parseSpicyLyricsRejectsNoticeOnlyPayload() {
        JsonObject notice = new JsonObject();
        notice.addProperty("_notice", "please update spicy lyrics");
        JsonObject result = new JsonObject();
        result.addProperty("httpStatus", 200);
        result.addProperty("format", "json");
        result.add("data", notice);

        assertThrows(IllegalStateException.class,
                () -> parser.parseSpicyLyrics(null, track, queryResponse(result).toString(), false));
    }

    @Test
    public void parseSyllableLyricsUsesTrailingSpanSpaceAsWordBoundary() {
        JsonObject result = new JsonObject();
        result.addProperty("httpStatus", 200);
        result.addProperty("format", "json");
        result.add("data", syllableLyricsWithSpanSpaces());

        LyricsDocument doc = parser.parseSpicyLyrics(null, track, queryResponse(result).toString(), false);

        LyricsLine line = doc.lines.get(0);
        assertEquals("점점 내 모습이", line.text);
        assertEquals("점", line.syllables.get(0).text);
        assertTrue(line.syllables.get(0).partOfWord);
        assertEquals("점", line.syllables.get(1).text);
        assertEquals("점 ", line.syllables.get(1).sourceText);
        assertFalse(line.syllables.get(1).partOfWord);
        assertEquals("내", line.syllables.get(2).text);
        assertEquals("내 ", line.syllables.get(2).sourceText);
        assertFalse(line.syllables.get(2).partOfWord);
        assertEquals("모", line.syllables.get(3).text);
        assertTrue(line.syllables.get(3).partOfWord);
    }

    private static JsonObject queryResponse(JsonObject result) {
        JsonObject query = new JsonObject();
        query.add("result", result);
        JsonArray queries = new JsonArray();
        queries.add(query);
        JsonObject root = new JsonObject();
        root.add("queries", queries);
        return root;
    }

    private static JsonObject unpackedStaticLyrics() {
        JsonObject line = new JsonObject();
        line.addProperty("Text", "hello");
        JsonArray lines = new JsonArray();
        lines.add(line);
        JsonObject lyrics = new JsonObject();
        lyrics.addProperty("Type", "Static");
        lyrics.add("Lines", lines);
        return lyrics;
    }

    private static JsonArray packedStaticLyrics() {
        JsonArray values = new JsonArray();
        values.add("Type");
        values.add("Static");
        values.add("Lines");
        values.add("Text");
        values.add("hello");

        JsonArray stream = new JsonArray();
        stream.add(-1);
        stream.add(2);
        stream.add(0);
        stream.add(2);
        stream.add(1);
        stream.add(-5);
        stream.add(-1);
        stream.add(1);
        stream.add(3);
        stream.add(4);

        JsonArray packed = new JsonArray();
        packed.add(values);
        packed.add(stream);
        return packed;
    }

    private static JsonObject syllableLyricsWithSpanSpaces() {
        JsonArray syllables = new JsonArray();
        syllables.add(syllable("점", true, 22.804, 23.167));
        syllables.add(syllable("점 ", true, 23.167, 23.304));
        syllables.add(syllable("내 ", true, 23.304, 23.429));
        syllables.add(syllable("모", true, 23.429, 23.589));
        syllables.add(syllable("습", true, 23.589, 23.792));
        syllables.add(syllable("이", true, 23.792, 23.892));

        JsonObject lead = new JsonObject();
        lead.addProperty("Text", "점점 내 모습이");
        lead.addProperty("StartTime", 22.804);
        lead.addProperty("EndTime", 23.892);
        lead.add("Syllables", syllables);

        JsonObject item = new JsonObject();
        item.addProperty("Type", "Vocal");
        item.add("Lead", lead);

        JsonArray content = new JsonArray();
        content.add(item);

        JsonObject lyrics = new JsonObject();
        lyrics.addProperty("Type", "Syllable");
        lyrics.add("Content", content);
        return lyrics;
    }

    private static JsonObject syllable(String text, boolean partOfWord, double start, double end) {
        JsonObject object = new JsonObject();
        object.addProperty("Text", text);
        object.addProperty("IsPartOfWord", partOfWord);
        object.addProperty("StartTime", start);
        object.addProperty("EndTime", end);
        return object;
    }
}
