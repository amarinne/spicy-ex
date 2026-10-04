package com.eza.spicyex.auto;

import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public class AutoCreditWindowRegressionTest {
    @Test public void capStartsWhenCreditAppearsEvenAfterSeekingDeepIntoOutro() {
        AutoResponseCredit.Window window = new AutoResponseCredit.Window();
        assertFalse(tick(window, "song", 8699, 1000, true, true));
        assertTrue(tick(window, "song", 60000, 1001, true, true));
        assertTrue(tick(window, "song", 69999, 11000, true, true));
        assertEquals(1, window.remainingMs(11000));
        assertFalse(tick(window, "song", 70000, 11001, true, true));
        assertEquals(0, window.remainingMs(11001));
        assertFalse(tick(window, "song", 70001, 11002, true, true));
    }

    @Test public void repeatedPacketsAndNewViewsUseOneEpisodeDeadline() {
        AutoResponseCredit.Window host = new AutoResponseCredit.Window();
        assertTrue(tick(host, "song", 8700, 1000, true, true));
        for (long now = 1100; now < 11000; now += 100) {
            // The same host decision feeds every replacement view and its native slot.
            boolean firstView = tick(host, "song", 8700 + now - 1000, now, true, true);
            boolean resizedView = tick(host, "song", 8700 + now - 1000, now, true, true);
            boolean remountedView = tick(host, "song", 8700 + now - 1000, now, true, true);
            assertTrue(firstView);
            assertEquals(firstView, resizedView);
            assertEquals(firstView, remountedView);
        }
        assertFalse(tick(host, "song", 18700, 11000, true, true));
        assertFalse(tick(host, "song", 18700, 12000, true, true));
    }

    @Test public void delayedMountDoesNotConsumeCreditAndLaterRemountDoesNotRestartIt() {
        AutoResponseCredit.Window host = new AutoResponseCredit.Window();
        assertTrue(host.update("song", true, true, true, 8000, 20000, true, false, 1000));
        assertTrue(host.update("song", true, true, true, 8000, 40000, true, false, 21000));
        assertTrue(host.update("song", true, true, true, 8000, 40100, true, true, 21100));
        assertTrue(host.update("song", true, true, true, 8000, 41100, true, false, 22100));
        assertTrue(host.update("song", true, true, true, 8000, 45100, true, true, 26100));
        assertFalse(host.update("song", true, true, true, 8000, 50100, true, false, 31100));
        assertFalse(host.update("song", true, true, true, 8000, 50200, true, true, 31200));
    }

    @Test public void missingStaleOrExpiredPacketsCannotRestartTheClock() {
        AutoResponseCredit.Window window = new AutoResponseCredit.Window();
        assertTrue(tick(window, "song", 8700, 1000, true, true));
        assertFalse(tick(window, null, 0, 2000, false, false));
        assertFalse(tick(window, "song", 0, 3000, false, false));
        assertTrue(tick(window, "song", 15000, 10999, true, true));
        assertFalse(tick(window, "song", 16000, 11000, true, true));
        assertFalse(tick(window, "song", 17000, 12000, true, true));
    }

    @Test public void pauseReleasesCreditAndResumeDoesNotRearmAnExpiredOutro() {
        AutoResponseCredit.Window window = new AutoResponseCredit.Window();
        AutoPrototypePolicy.PlaybackHold playback = new AutoPrototypePolicy.PlaybackHold();
        AutoArtworkRelease release = new AutoArtworkRelease();
        assertTrue(boundary(window, playback, release, "song", 8700, 1000, true));
        assertTrue(boundary(window, playback, release, "song", 8700, 1100, false));
        assertTrue(boundary(window, playback, release, "song", 8700, 4099, false));
        assertFalse(boundary(window, playback, release, "song", 8700, 4100, false));
        assertTrue(release.retainLyrics(4100));
        assertFalse(release.retainLyrics(4275));
        assertTrue(boundary(window, playback, release, "song", 8700, 6000, true));
        assertFalse(release.running());
        assertFalse(boundary(window, playback, release, "song", 8700, 11000, true));
        assertTrue(release.running());
        assertFalse(boundary(window, playback, release, "song", 8700, 12000, true));
        assertFalse(release.running());
    }

    @Test public void songChangeImmediatelyEndsCreditAndIntroSeekAllowsAnotherEpisode() {
        AutoResponseCredit.Window window = new AutoResponseCredit.Window();
        AutoArtworkRelease release = new AutoArtworkRelease();
        AutoPrototypePolicy.PlaybackHold playback = new AutoPrototypePolicy.PlaybackHold();
        assertTrue(boundary(window, playback, release, "song", 8700, 1000, true));
        assertFalse(boundary(window, playback, release, "next", 0, 1001, true));
        assertFalse(release.running());
        assertFalse(release.retainLyrics(1001));
        assertEquals(1f, release.nativeAlpha(1001), 0f);
        assertTrue(boundary(window, playback, release, "next", 8700, 1002, true));
        assertFalse(boundary(window, playback, release, "next", 8700, 11002, true));
        assertFalse(boundary(window, playback, release, "next", 1000, 11003, true));
        assertTrue(boundary(window, playback, release, "next", 8700, 11004, true));
        assertTrue(boundary(window, playback, release, "next", 18699, 21003, true));
        assertFalse(boundary(window, playback, release, "next", 18700, 21004, true));
    }

    @Test public void creditNeedsResolvedSyncedDataAndStartsOnlyWhenPlaybackCanTakeOver() {
        AutoResponseCredit.Window window = new AutoResponseCredit.Window();
        assertFalse(tick(window, "song", 8700, 1000, true, false));
        assertFalse(window.update("song", true, true, false, 8000, 8700, true, true, 1001));
        assertFalse(window.update("song", true, true, true, 0, 8700, true, true, 1002));
        assertFalse(window.update("song", true, true, true, 8000, 8700, false, true, 1003));
        assertTrue(tick(window, "song", 8700, 20000, true, true));
    }

    @Test public void writersAndResponseCreditsRemainPlainAndDoNotNeedContributorLinks() {
        assertEquals("Written by: Casey Luong, Elie Rizk\nLyrics from Apple Music",
                AutoResponseCredit.text(" Casey Luong, Elie Rizk ", Arrays.asList("Lyrics from Apple Music"), "\n"));
        assertEquals("Written by: Casey Luong\nLyrics from Spicy Lyrics\nuploaded by spikerko, made by gc",
                AutoResponseCredit.text("Casey Luong", Arrays.asList("Lyrics from Spicy Lyrics", "uploaded by spikerko", "made by gc"), "\n"));
        assertEquals("Lyrics from Spicy Lyrics\nuploaded by spikerko, made by gc",
                AutoResponseCredit.text(null, Arrays.asList("Lyrics from Spicy Lyrics", "uploaded by spikerko", "made by gc"), "\n"));
        assertEquals("Written by: Casey Luong", AutoResponseCredit.text("Casey Luong", null, "\n"));
        assertTrue(AutoResponseCredit.hasCredit("Casey Luong", null));
        assertFalse(AutoResponseCredit.hasCredit("  ", null));
    }

    @Test public void stalePacketForAnotherSongCannotRearmTheCurrentOutro() {
        AutoResponseCredit.Window window = new AutoResponseCredit.Window();
        assertTrue(tick(window, "song", 8700, 1000, true, true));
        assertFalse(tick(window, "stale-other-song", 0, 2000, false, false));
        assertFalse(tick(window, "song", 18700, 11000, true, true));
        assertFalse(tick(window, "song", 19700, 12000, true, true));
    }

    private static boolean tick(AutoResponseCredit.Window window, String session, long position,
            long now, boolean valid, boolean eligible) {
        return window.update(session, valid, eligible, true, 8000, position, true, true, now);
    }

    private static boolean boundary(AutoResponseCredit.Window window, AutoPrototypePolicy.PlaybackHold playback,
            AutoArtworkRelease release, String session, long position, long now, boolean playing) {
        boolean held = playback.update(true, playing, now);
        boolean credit = tick(window, session, position, now, true, held);
        boolean nativeArtwork = AutoLyricPresentation.nativeArtwork(true, true, 4000, 8000, position, false, credit);
        release.update(session, held && !nativeArtwork, true, true, now);
        return credit;
    }

}
