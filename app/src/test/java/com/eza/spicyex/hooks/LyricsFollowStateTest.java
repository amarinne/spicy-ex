package com.eza.spicyex.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LyricsFollowStateTest {
    @Test
    public void manualSuspensionPersistsUntilExplicitClear() {
        LyricsFollowState state = new LyricsFollowState(() -> 1000L);
        state.setTouching(true);
        state.setTouching(false);

        assertTrue(state.isHoldingNow());
        state.clearHold();
        assertFalse(state.isHoldingNow());
    }

    @Test
    public void resetActiveClearsManualSuspensionForNewDocument() {
        LyricsFollowState state = new LyricsFollowState(() -> 1000L);
        state.setTouching(true);

        state.resetActive();

        assertFalse(state.isHoldingNow());
    }

    @Test
    public void autoResumeProgressTracksElapsedCooldown() {
        long[] time = {1000L};
        LyricsFollowState state = new LyricsFollowState(() -> time[0]);

        // When not suspended, progress is 0
        assertEquals(0f, state.autoResumeProgress(750L), 0.001f);
        assertFalse(state.isTouching());

        // When touching, progress is 0
        state.setTouching(true);
        assertTrue(state.isTouching());
        assertEquals(0f, state.autoResumeProgress(750L), 0.001f);

        // Released touch at t=1000
        state.setTouching(false);
        assertFalse(state.isTouching());
        assertEquals(0f, state.autoResumeProgress(750L), 0.001f);

        // Advance 375ms (halfway)
        time[0] = 1375L;
        assertEquals(0.5f, state.autoResumeProgress(750L), 0.001f);

        // Advance to full 750ms
        time[0] = 1750L;
        assertEquals(1.0f, state.autoResumeProgress(750L), 0.001f);

        // Clamped to 1.0f past cooldown
        time[0] = 2500L;
        assertEquals(1.0f, state.autoResumeProgress(750L), 0.001f);

        // Invalid cooldown returns 0
        assertEquals(0f, state.autoResumeProgress(0L), 0.001f);
    }
}
