package com.eza.spicyex.lyrics.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.lyrics.LyricsLine;
import org.junit.Test;

public class CanonicalSourceCacheEntryTest {

    @Test
    public void summaryReadsTitleSourceAndFirstLineFromAnEncodedRecord() {
        LyricsDocument document = new LyricsDocument();
        document.trackId = "abc";
        document.provider = "LRCLIB";
        document.selectedSource = "Apple Music";
        LyricsLine first = new LyricsLine();
        first.text = "  Hello there ";
        document.lines.add(first);
        LyricsLine second = new LyricsLine();
        second.text = "Second";
        document.lines.add(second);
        String raw = CanonicalSourceCodec.encode(document, 1, "digest", 1234L, "",
                "Song", "Artist", "spotify:track:abc");

        CanonicalSourceCache.Entry entry = CanonicalSourceCache.summarize("key", raw, 99L, 5L);

        assertEquals("Song", entry.title);
        assertEquals("Artist", entry.artist);
        assertEquals("spotify:track:abc", entry.trackUri);
        assertEquals("abc", entry.trackId);
        assertEquals("Apple Music", entry.source);
        assertEquals("Hello there", entry.firstLine);
        assertEquals(1234L, entry.savedAtMs);
        assertEquals(99L, entry.bytes);
        // The record still decodes as before.
        assertEquals(2, CanonicalSourceCodec.decode(raw).document.lines.size());
    }

    @Test
    public void olderRecordsWithoutTitlesStillList() {
        LyricsDocument document = new LyricsDocument();
        document.provider = "Musixmatch";
        LyricsLine line = new LyricsLine();
        line.text = "Only line";
        document.lines.add(line);
        String raw = CanonicalSourceCodec.encode(document, 1, "d", 7L, "");

        CanonicalSourceCache.Entry entry = CanonicalSourceCache.summarize("key", raw, 1L, 5L);

        assertEquals("", entry.title);
        assertEquals("Musixmatch", entry.source);
        assertEquals("Only line", entry.firstLine);
        assertNull(CanonicalSourceCache.summarize("key", "not json", 1L, 5L));
    }
}
