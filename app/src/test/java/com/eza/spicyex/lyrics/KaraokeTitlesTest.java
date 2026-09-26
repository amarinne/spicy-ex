package com.eza.spicyex.lyrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.eza.spicyex.SpotifyTrack;

import org.junit.Test;

public class KaraokeTitlesTest {
    private static SpotifyTrack track(String title, String artist) {
        return new SpotifyTrack(title, artist, "Album", "spotify:track:abc", 0, "", 0, null,
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
}
