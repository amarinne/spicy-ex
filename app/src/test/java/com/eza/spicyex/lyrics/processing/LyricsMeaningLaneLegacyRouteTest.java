package com.eza.spicyex.lyrics.processing;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The lane asks for a buying run when output is missing or older-epoch. */
public final class LyricsMeaningLaneLegacyRouteTest {

    @Test public void missingOutputNeedsTheBuyingRun() {
        assertTrue(LyricsMeaningLane.shouldRequestMeaning(false, false));
    }

    @Test public void olderEpochOutputMayBeReplaced() {
        assertTrue(LyricsMeaningLane.shouldRequestMeaning(true, true));
    }

    @Test public void currentEpochOutputIsNeverBoughtAgain() {
        assertFalse(LyricsMeaningLane.shouldRequestMeaning(true, false));
    }
}
