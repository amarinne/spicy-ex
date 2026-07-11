package com.eza.spicyex.hooks;

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
}
