package com.eza.spicyex.lyrics;

import com.eza.spicyex.SpotifyTrack;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class LyricsParserProviderTranslationTest {
    @Test
    public void parserKeepsProviderTranslationAsTargetAwareSidecar() {
        JsonObject line = new JsonObject();
        line.addProperty("Text", "你好");
        line.addProperty("TranslatedText", "Hello");
        line.addProperty("TranslatedTextLanguage", "en");
        JsonArray lines = new JsonArray();
        lines.add(line);
        JsonObject lyrics = new JsonObject();
        lyrics.addProperty("Type", "Static");
        lyrics.addProperty("Language", "zh");
        lyrics.add("Lines", lines);

        JsonObject result = new JsonObject();
        result.addProperty("httpStatus", 200);
        result.addProperty("format", "json");
        result.add("data", lyrics);
        JsonObject query = new JsonObject();
        query.add("result", result);
        JsonArray queries = new JsonArray();
        queries.add(query);
        JsonObject root = new JsonObject();
        root.add("queries", queries);

        SpotifyTrack track = new SpotifyTrack(
                "Song", "Artist", "Album", "spotify:track:test", 0, "", 0, null, 180000, false);
        LyricsDocument document = new LyricsParser(null).parseSpicyLyrics(null, track, root.toString(), false);

        assertEquals("", document.lines.get(0).translatedText);
        assertEquals("Hello", document.lines.get(0).providerTranslatedText);
        assertEquals("en", document.lines.get(0).providerTranslationLanguage);
    }
}
