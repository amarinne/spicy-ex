package com.eza.spicyex.lyrics;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class LyricsMeaningLaneRoutingTest {
    @Test public void onDemandAiLeavesPendingTranslationOnGoogle() {
        assertFalse(LyricsMeaningLane.shouldUseAi(true, false, false, true));
    }

    @Test public void explicitAndAutomaticAiRequestsUseAi() {
        assertTrue(LyricsMeaningLane.shouldUseAi(true, true, false, true));
        assertTrue(LyricsMeaningLane.shouldUseAi(true, false, true, true));
    }

    @Test public void aiDisabledNeverUsesAi() {
        assertFalse(LyricsMeaningLane.shouldUseAi(false, true, true, true));
    }
}
