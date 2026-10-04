package com.eza.spicyex.auto;

import org.junit.Test;
import static org.junit.Assert.*;

public class AutoArtworkReleaseTest {
    @Test public void releaseFadesTheOldSlotBeforeRestoringNativeContent() {
        AutoArtworkRelease release = new AutoArtworkRelease();
        release.update("song", true, true, true, 0);
        release.update("song", false, true, true, 1000);
        assertTrue(release.retainLyrics(1000));
        assertEquals(1f, release.lyricAlpha(1000), 0f);
        assertEquals(0f, release.nativeAlpha(1000), 0f);
        release.update("song", false, true, true, 1100);
        assertTrue(release.retainLyrics(1100));
        assertTrue(release.lyricAlpha(1100) > 0 && release.lyricAlpha(1100) < 1);
        assertFalse(release.retainLyrics(1175));
        assertEquals(0f, release.lyricAlpha(1175), 0f);
        assertEquals(0f, release.nativeAlpha(1175), 0f);
        assertEquals(.5f, release.nativeAlpha(1262) , .01f);
        release.update("song", false, true, true, 1350);
        assertFalse(release.running());
        assertEquals(1f, release.nativeAlpha(1350), 0f);
    }
    @Test public void resumeNewSongDisableAndReducedMotionCancelOldContent() {
        for (int cancellation = 0; cancellation < 4; cancellation++) {
            AutoArtworkRelease release = new AutoArtworkRelease();
            release.update("song", true, true, true, 0);
            release.update("song", false, true, true, 1000);
            release.update(cancellation == 1 ? "new" : "song", cancellation == 0,
                    cancellation != 2, cancellation != 3, 1050);
            assertFalse(release.running());
            assertFalse(release.retainLyrics(1050));
            assertEquals(1f, release.nativeAlpha(1050), 0f);
        }
    }
}
