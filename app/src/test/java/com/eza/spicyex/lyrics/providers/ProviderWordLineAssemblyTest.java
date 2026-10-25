package com.eza.spicyex.lyrics.providers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.eza.spicyex.SpotifyTrack;
import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.lyrics.LyricsLine;
import com.eza.spicyex.lyrics.SyllableSegment;
import com.google.gson.JsonObject;

import org.junit.Test;

public class ProviderWordLineAssemblyTest {
    private static final SpotifyTrack TRACK = new SpotifyTrack(
            "Song", "Artist", "Album", "spotify:track:abc123", 0, "", 0, null, 180000, false);

    private final LyricsParser parser = new LyricsParser(null);

    @Test
    public void wordProvidersKeepEquivalentAuthoredTextTimingAndCanonicalSpans() {
        LyricsDocument netease = parser.parseNeteaseWordLyrics(null, TRACK, neteaseWordPayload(
                "[1000,2000](1000,500,0)Hello(1500,500,0) (2000,400,0)world(2400,600,0)!"));
        LyricsDocument qq = parser.parseQqWordLyrics(null, TRACK,
                LyricsParser.buildQqQrcResponseForTest(
                        "[1000,2000]Hello(1000,500) (1500,500)world(2000,400)!(2400,600)", ""));

        assertAssembledLine(netease);
        assertAssembledLine(qq);
    }

    @Test
    public void emptyAndWhitespaceOnlyWordRowsAreSkippedBeforeAssembly() {
        LyricsDocument netease = parser.parseNeteaseWordLyrics(null, TRACK, neteaseWordPayload(
                "[0,500]\n[500,500](500,500,0)   \n[1000,1000](1000,900,0)ok"));
        LyricsDocument qq = parser.parseQqWordLyrics(null, TRACK,
                LyricsParser.buildQqQrcResponseForTest(
                        "[0,500]\n[500,500]   (500,500)\n[1000,1000]ok(1000,900)", ""));

        assertOnlyRemainingLine(netease);
        assertOnlyRemainingLine(qq);
    }

    @Test
    public void qqTitleAndCreditRowsStayFilteredBeforeAssembly() {
        LyricsDocument doc = parser.parseQqWordLyrics(null, TRACK,
                LyricsParser.buildQqQrcResponseForTest(
                        "[0,1000]Song - Artist(0,1000)\n"
                                + "[1000,1000]作词: Bob(1000,1000)\n"
                                + "[2000,1000]actual(2000,500) line(2500,500)", ""));

        assertNotNull(doc);
        assertEquals(1, doc.lines.size());
        assertEquals("actual line", doc.lines.get(0).text);
        assertEquals("Bob", doc.songWriters);
        assertEquals(2, doc.lines.get(0).syllables.size());
    }

    private static void assertAssembledLine(LyricsDocument doc) {
        assertNotNull(doc);
        assertEquals(1, doc.lines.size());
        LyricsLine line = doc.lines.get(0);
        assertEquals("Hello world!", line.text);
        assertEquals(1000, line.startMs);
        assertEquals(3000, line.endMs);
        assertEquals(3, line.syllables.size());

        SyllableSegment hello = line.syllables.get(0);
        assertEquals("0", hello.spanId);
        assertEquals("Hello", hello.text);
        assertEquals(1000, hello.startMs);
        assertEquals(1500, hello.endMs);
        assertEquals(500, hello.totalMs);
        assertEquals(0, hello.canonicalStartCp);
        assertEquals(5, hello.canonicalEndCp);
        assertTrue(hello.boundaryAfter);

        SyllableSegment world = line.syllables.get(1);
        assertEquals("1", world.spanId);
        assertEquals("world", world.text);
        assertEquals(2000, world.startMs);
        assertEquals(2400, world.endMs);
        assertEquals(400, world.totalMs);
        assertEquals(6, world.canonicalStartCp);
        assertEquals(11, world.canonicalEndCp);
        assertFalse(world.boundaryAfter);

        SyllableSegment mark = line.syllables.get(2);
        assertEquals("2", mark.spanId);
        assertEquals("!", mark.text);
        assertEquals(2400, mark.startMs);
        assertEquals(3000, mark.endMs);
        assertEquals(600, mark.totalMs);
        assertEquals(11, mark.canonicalStartCp);
        assertEquals(12, mark.canonicalEndCp);
        assertFalse(mark.boundaryAfter);
    }

    private static void assertOnlyRemainingLine(LyricsDocument doc) {
        assertNotNull(doc);
        assertEquals(1, doc.lines.size());
        LyricsLine line = doc.lines.get(0);
        assertEquals("ok", line.text);
        assertEquals(1000, line.startMs);
        assertEquals(2000, line.endMs);
        assertEquals(1, line.syllables.size());
        assertEquals("0", line.syllables.get(0).spanId);
        assertEquals(1000, line.syllables.get(0).startMs);
        assertEquals(1900, line.syllables.get(0).endMs);
        assertEquals(0, line.syllables.get(0).canonicalStartCp);
        assertEquals(2, line.syllables.get(0).canonicalEndCp);
    }

    private static String neteaseWordPayload(String yrc) {
        JsonObject yrcObject = new JsonObject();
        yrcObject.addProperty("lyric", yrc);
        JsonObject root = new JsonObject();
        root.add("yrc", yrcObject);
        return root.toString();
    }
}
