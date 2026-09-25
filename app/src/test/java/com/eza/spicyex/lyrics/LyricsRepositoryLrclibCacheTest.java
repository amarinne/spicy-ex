package com.eza.spicyex.lyrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.eza.spicyex.SpotifyTrack;

import org.junit.Test;

/**
 * The LRCLIB raw cache replays what the provider already returned, so usability is decided by
 * parsing: anything that no longer yields lines has to return null and let the network run
 * underneath it instead of surfacing as an LRCLIB failure.
 */
public class LyricsRepositoryLrclibCacheTest {

    private static final SpotifyTrack TRACK = new SpotifyTrack(
            "Song", "Artist", "Album", "spotify:track:test", 0, "", 0, null, 180000, false);

    private static LyricsDocument parse(String raw) {
        return LyricsRepository.parseCachedLrclibRaw(new LyricsParser(null), null, TRACK, raw);
    }

    @Test
    public void storedSyncedSearchResponseStillDeliversLines() {
        LyricsDocument doc = parse("[{\"trackName\":\"Song\","
                + "\"syncedLyrics\":\"[00:01.00] hello\\n[00:02.00] world\\n\","
                + "\"plainLyrics\":\"hello\\nworld\"}]");

        assertNotNull(doc);
        assertEquals(2, doc.lines.size());
        assertEquals("hello", doc.lines.get(0).text);
        assertEquals("Line", doc.type);
        assertEquals("lrclib", doc.fetchSource);
    }

    @Test
    public void corruptOrForeignPayloadFallsBackToTheNetwork() {
        assertNull(parse("not json at all"));
        assertNull(parse("{\"syncedLyrics\":\"[00:01.00] hello\"}"));
        assertNull(parse("[]"));
        assertNull(parse(""));
        assertNull(parse(null));
    }

    @Test
    public void entriesWithoutTimedLinesAreNotDelivered() {
        assertNull(parse("[{\"syncedLyrics\":\"no timestamp here\","
                + "\"plainLyrics\":\"no timestamp here\"}]"));
    }

    @Test
    public void entriesWithoutAnyLyricsAreNotDelivered() {
        assertNull(parse("[{\"syncedLyrics\":\"\",\"plainLyrics\":\"\"}]"));
    }
}
