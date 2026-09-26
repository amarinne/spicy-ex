package com.eza.spicyex.lyrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.eza.spicyex.SpotifyTrack;
import org.junit.Test;

public class KaraokeTitlesTest {

    private static SpotifyTrack track(String title, String artist, String album) {
        return new SpotifyTrack(title, artist, album, "spotify:track:x", 0L, "", 0L, "", 200_000L, false);
    }

    @Test
    public void karaokeLabelSearchesByTitleAlone() {
        SpotifyTrack search = KaraokeTitles.forLyricsSearch(track(
                "Try Everything (From \"Zootopia\") [Karaoke Version]", "Urock Karaoke",
                "Try Everything (From \"Zootopia\") [Karaoke Version]"));
        assertEquals("Try Everything", search.title);
        assertEquals("", search.artist);
        assertEquals("", search.album);
        assertEquals("spotify:track:x", search.uri);
    }

    @Test
    public void performerComesFromTheAlbumWhenTheTitleHasNone() {
        SpotifyTrack search = KaraokeTitles.forLyricsSearch(track(
                "Good Time (Karaoke Version)", "High Frequency Karaoke",
                "Good Time (In the Style of Owl City & Carly Rae Jepsen) [Karaoke Version]"));
        assertEquals("Good Time", search.title);
        assertEquals("Owl City & Carly Rae Jepsen", search.artist);
    }

    @Test
    public void performerInTheTitleWins() {
        SpotifyTrack search = KaraokeTitles.forLyricsSearch(track(
                "Shallow (Originally Performed by Lady Gaga & Bradley Cooper) [Karaoke Version]",
                "Sing2Piano", "Piano Karaoke Hits"));
        assertEquals("Shallow", search.title);
        assertEquals("Lady Gaga & Bradley Cooper", search.artist);
    }

    @Test
    public void officialInstrumentalKeepsItsArtist() {
        SpotifyTrack search = KaraokeTitles.forLyricsSearch(track(
                "Blinding Lights - Instrumental", "The Weeknd", "After Hours (Instrumentals)"));
        assertEquals("Blinding Lights", search.title);
        assertEquals("The Weeknd", search.artist);
    }

    @Test
    public void unversionedTracksAreUntouched() {
        SpotifyTrack original = track("Try Everything - From \"Zootopia\"", "Shakira", "Zootopia");
        assertSame(original, KaraokeTitles.forLyricsSearch(original));
        assertFalse(KaraokeTitles.isKaraokeVersion(original.title));
        assertTrue(KaraokeTitles.isKaraokeVersion("夜に駆ける (カラオケ)"));
    }

    @Test
    public void performerTags() {
        assertEquals("Owl City", KaraokeTitles.performer("Karaoke Hits in the Style of Owl City"));
        assertNull(KaraokeTitles.performer("Zootopia (Original Soundtrack)"));
    }
}
