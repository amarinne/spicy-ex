package com.eza.spicyex.lyrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.eza.spicyex.SpotifyTrack;

import org.junit.Test;

public class KaraokeTitlesTest {
    private static SpotifyTrack track(String title, String artist) {
        return track(title, artist, "Album");
    }

    private static SpotifyTrack track(String title, String artist, String album) {
        return new SpotifyTrack(title, artist, album, "spotify:track:abc", 0, "", 0, null,
                180000, false);
    }

    @Test
    public void plainTitlesPassThroughUntouched() {
        SpotifyTrack track = track("Song", "Artist");
        assertTrue(KaraokeTitles.forLyricsSearch(track, true) == track);
        assertFalse(KaraokeTitles.isKaraokeVersion("Song"));
    }

    @Test
    public void disabledOriginalLookupKeepsThePlayingTitle() {
        SpotifyTrack track = track("Song (Karaoke Version)", "Karaoke");
        assertTrue(KaraokeTitles.forLyricsSearch(track, false) == track);
    }

    @Test
    public void bracketAndDashVersionTagsAreRemoved() {
        assertEquals("Song", KaraokeTitles.forLyricsSearch(
                track("Song (Karaoke Version)", "A"), true).title);
        assertEquals("Song", KaraokeTitles.forLyricsSearch(
                track("Song - Instrumental", "A"), true).title);
        assertEquals("Song", KaraokeTitles.forLyricsSearch(
                track("Song（カラオケ）", "A"), true).title);
        assertEquals("Song", KaraokeTitles.forLyricsSearch(
                track("Song [Off Vocal]", "A"), true).title);
    }

    @Test
    public void karaokeLabelReleasesRecoverTheOriginalPerformer() {
        SpotifyTrack rewritten = KaraokeTitles.forLyricsSearch(
                track("Song (Karaoke Version) (Originally Performed by Real)", "Karaoke"), true);
        assertEquals("Song", rewritten.title);
        assertEquals("Real", rewritten.artist);
    }

    @Test
    public void rewrittenSearchKeepsThePlayingUri() {
        SpotifyTrack rewritten =
                KaraokeTitles.forLyricsSearch(track("Song (Karaoke Version)", "A"), true);
        assertEquals("spotify:track:abc", rewritten.uri);
    }

    @Test
    public void karaokeLabelSearchesByTitleAlone() {
        SpotifyTrack search = KaraokeTitles.forLyricsSearch(track(
                "Try Everything (From \"Zootopia\") [Karaoke Version]", "Urock Karaoke",
                "Try Everything (From \"Zootopia\") [Karaoke Version]"), true);
        assertEquals("Try Everything", search.title);
        assertEquals("", search.artist);
        assertEquals("", search.album);
        assertEquals("spotify:track:abc", search.uri);
    }

    @Test
    public void performerComesFromTheAlbumWhenTheTitleHasNone() {
        SpotifyTrack search = KaraokeTitles.forLyricsSearch(track(
                "Good Time (Karaoke Version)", "High Frequency Karaoke",
                "Good Time (In the Style of Owl City & Carly Rae Jepsen) [Karaoke Version]"), true);
        assertEquals("Good Time", search.title);
        assertEquals("Owl City & Carly Rae Jepsen", search.artist);
    }

    @Test
    public void performerInTheTitleWins() {
        SpotifyTrack search = KaraokeTitles.forLyricsSearch(track(
                "Shallow (Originally Performed by Lady Gaga & Bradley Cooper) [Karaoke Version]",
                "Sing2Piano", "Piano Karaoke Hits"), true);
        assertEquals("Shallow", search.title);
        assertEquals("Lady Gaga & Bradley Cooper", search.artist);
    }

    @Test
    public void officialInstrumentalKeepsItsArtist() {
        SpotifyTrack search = KaraokeTitles.forLyricsSearch(track(
                "Blinding Lights - Instrumental", "The Weeknd", "After Hours (Instrumentals)"), true);
        assertEquals("Blinding Lights", search.title);
        assertEquals("The Weeknd", search.artist);
    }

    @Test
    public void unversionedTracksAreUntouched() {
        SpotifyTrack original = track("Try Everything - From \"Zootopia\"", "Shakira", "Zootopia");
        assertSame(original, KaraokeTitles.forLyricsSearch(original, true));
        assertFalse(KaraokeTitles.isKaraokeVersion(original.title));
        assertTrue(KaraokeTitles.isKaraokeVersion("夜に駆ける (カラオケ)"));
    }

    @Test
    public void performerTags() {
        assertEquals("Owl City", KaraokeTitles.performer("Karaoke Hits in the Style of Owl City"));
        assertNull(KaraokeTitles.performer("Zootopia (Original Soundtrack)"));
    }
}
