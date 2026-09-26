package com.eza.spicyex.hooks;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class LyricsSessionSeatTransitionTest {
    @Test
    public void policyChangeRetiresIneligibleSeatBeforeReplacementFailure() {
        LyricsSessionSeatTransition.Action action = LyricsSessionSeatTransition.reconcile(
                true, false, true);

        assertEquals(LyricsSessionSeatTransition.Action.RETIRE_LOADING, action);
        boolean displayedBasePresent = action != LyricsSessionSeatTransition.Action.RETIRE_LOADING
                && action != LyricsSessionSeatTransition.Action.RETIRE_NO_LYRICS;
        assertTrue(LyricsSessionSeatTransition.fetchFailureNeedsNoLyrics(displayedBasePresent));
    }

    @Test
    public void manualOrOtherwiseEligibleSeatIsPublishedInsteadOfRetired() {
        assertEquals(LyricsSessionSeatTransition.Action.PUBLISH_SEAT,
                LyricsSessionSeatTransition.reconcile(true, true, false));
    }

    @Test
    public void policyChangeWithoutReplacementSettlesAsNoLyrics() {
        assertEquals(LyricsSessionSeatTransition.Action.RETIRE_NO_LYRICS,
                LyricsSessionSeatTransition.reconcile(true, false, false));
    }
}
