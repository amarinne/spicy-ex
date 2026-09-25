package com.eza.spicyex.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.eza.spicyex.lyrics.LyricsDocument;

import org.junit.Test;

/** The Sound toggle's two host decisions, off-device. */
public final class SoundToggleHostDecisionTest {

    @Test public void aCycleOnlyRunsWhereTheLayerIsSelectable() {
        assertTrue(SoundModeCyclePolicy.mayCycle(true));
        assertFalse(SoundModeCyclePolicy.mayCycle(false));
    }

    @Test public void aCycleClearsEveryAiStateItIsAboutToReplace() {
        assertTrue(SoundModeCyclePolicy.clearsStaleAiReading(reading(true, false, "")));
        assertTrue(SoundModeCyclePolicy.clearsStaleAiReading(reading(false, true, "")));
        assertTrue(SoundModeCyclePolicy.clearsStaleAiReading(reading(false, false, "auth_rejected")));
    }

    @Test public void aCycleLeavesAnUntouchedSoundLayerAlone() {
        assertFalse(SoundModeCyclePolicy.clearsStaleAiReading(reading(false, false, "")));
        assertFalse(SoundModeCyclePolicy.clearsStaleAiReading(null));
    }

    @Test public void onlyALongPressMayAskForAPaidReading() {
        assertEquals(SoundToggleRouter.Action.CYCLE_LOCAL_MODE,
                SoundToggleRouter.forGesture(false));
        assertEquals(SoundToggleRouter.Action.OPEN_AI_PANEL,
                SoundToggleRouter.forGesture(true));
    }

    private static LyricsDocument reading(boolean fromAi, boolean pending, String failureToken) {
        LyricsDocument document = new LyricsDocument();
        document.readingFromAi = fromAi;
        document.readingAiPending = pending;
        document.readingAiFailureToken = failureToken;
        return document;
    }
}
