package com.eza.spicyex.auto;

import org.junit.Test;
import static org.junit.Assert.*;

/** Exercises the presentation, playback hold, and native slot handoff together. */
public class AutoBoundaryReleaseRegressionTest {
    @Test public void creditOutroKeepsSlotUntilIntroSeekAndVocalResumeCancelsRelease() {
        AutoArtworkRelease release = new AutoArtworkRelease();
        AutoPrototypePolicy.PlaybackHold playback = new AutoPrototypePolicy.PlaybackHold();
        tick(release, playback, "song", 3499, 1000, true, true);
        assertFalse(release.retainLyrics(1000));
        tick(release, playback, "song", 3500, 1001, true, true);
        tick(release, playback, "song", 8699, 6200, true, true);
        tick(release, playback, "song", 8700, 6201, true, true);
        assertFalse(release.running());
        assertFalse(AutoLyricPresentation.nativeArtwork(true, true, 4000, 8000, 8700, false, true));

        // Seek from the plain credit back to the native opening, then resume vocals mid-fade.
        tick(release, playback, "song", 1000, 6300, true, true);
        assertTrue(release.retainLyrics(6300));
        assertFalse(release.retainLyrics(6475));
        tick(release, playback, "song", 5000, 6476, true, true);
        assertFalse(release.running());
        assertEquals(1f, release.lyricAlpha(6476), 0f);
    }

    @Test public void uncreditedOutroRestoresNativeOnceWithoutRestartingTheFade() {
        AutoArtworkRelease release = new AutoArtworkRelease();
        AutoPrototypePolicy.PlaybackHold playback = new AutoPrototypePolicy.PlaybackHold();
        tick(release, playback, "song", 8699, 1000, true, false);
        tick(release, playback, "song", 8700, 1001, true, false);
        assertTrue(release.retainLyrics(1001));
        tick(release, playback, "song", 8800, 1101, true, false);
        assertFalse(release.retainLyrics(1176));
        assertEquals(0f, release.nativeAlpha(1176), 0f);
        tick(release, playback, "song", 9050, 1351, true, false);
        assertFalse(release.running());
        assertEquals(1f, release.nativeAlpha(1351), 0f);
        tick(release, playback, "song", 10000, 2301, true, false);
        assertFalse(release.running());
        assertFalse(release.retainLyrics(2301));
    }

    @Test public void pausedCreditReleasesAtHoldDeadlineAndNewTrackCannotRetainOldSlot() {
        AutoArtworkRelease release = new AutoArtworkRelease();
        AutoPrototypePolicy.PlaybackHold playback = new AutoPrototypePolicy.PlaybackHold();
        tick(release, playback, "song", 8700, 1000, true, true);
        tick(release, playback, "song", 8700, 1100, false, true);
        tick(release, playback, "song", 8700, 4099, false, true);
        assertFalse(release.running());
        tick(release, playback, "song", 8700, 4100, false, true);
        assertTrue(release.retainLyrics(4100));
        tick(release, playback, "next-song", 0, 4101, true, false);
        assertFalse(release.running());
        assertFalse(release.retainLyrics(4101));
        assertEquals(1f, release.nativeAlpha(4101), 0f);
    }

    private static void tick(AutoArtworkRelease release, AutoPrototypePolicy.PlaybackHold playback,
            String session, long position, long now, boolean playing, boolean credit) {
        boolean held = playback.update(true, playing, now);
        boolean nativeArtwork = AutoLyricPresentation.nativeArtwork(
                true, true, 4000, 8000, position, false, credit);
        release.update(session, held && !nativeArtwork, true, true, now);
    }
}
