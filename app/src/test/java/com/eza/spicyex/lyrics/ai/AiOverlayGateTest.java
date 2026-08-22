package com.eza.spicyex.lyrics.ai;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.eza.spicyex.lyrics.session.LayerKind;

import org.junit.Before;
import org.junit.Test;

/**
 * Restore and the master switch: two ways to stop showing an answer, neither of which unbuys it.
 *
 * <p>The scoping is the whole design. Restore is aimed at one answer in front of the owner, so it
 * is keyed on the full identity and dies with the track or the process. A Restore that outlived
 * either would be a standing instruction to hide paid output on a track the owner pressed it on
 * once, weeks ago, with no way left to discover why their translation stopped appearing.
 */
public class AiOverlayGateTest {

    private static final String TRACK = "spotify:track:a";
    private static final String OTHER_TRACK = "spotify:track:b";
    private static final String CONFIG = "config-1";
    private static final String DIGEST = "digest-1";

    private AiOverlayGate gate;

    @Before public void setUp() {
        gate = new AiOverlayGate();
        gate.setEnabled(true);
    }

    @Test
    public void nothingIsAppliedWhileTheFeatureIsOff() {
        gate.setEnabled(false);
        assertFalse(gate.mayAutoApply(LayerKind.MEANING, TRACK, CONFIG, DIGEST));
    }

    @Test
    public void turningItOffReportsThatLiveOverlaysMustGo() {
        assertTrue("the caller has overlays on screen to drop", gate.setEnabled(false));
        assertFalse("already off; there is nothing left to drop", gate.setEnabled(false));
    }

    @Test
    public void aStoredAnswerCameBackWhenTheFeatureIsSwitchedOnAgain() {
        gate.setEnabled(false);
        gate.setEnabled(true);
        assertTrue("disabling drops overlays; it never deletes what was paid for",
                gate.mayAutoApply(LayerKind.MEANING, TRACK, CONFIG, DIGEST));
    }

    @Test
    public void restoreHidesExactlyTheAnswerItWasAimedAt() {
        gate.restore(LayerKind.MEANING, TRACK, CONFIG, DIGEST);

        assertFalse(gate.mayAutoApply(LayerKind.MEANING, TRACK, CONFIG, DIGEST));
        assertTrue("a different layer is a different answer",
                gate.mayAutoApply(LayerKind.SOUND, TRACK, CONFIG, DIGEST));
        assertTrue("a different model or preset is a different question",
                gate.mayAutoApply(LayerKind.MEANING, TRACK, "config-2", DIGEST));
        assertTrue("a re-fetched document is a different document",
                gate.mayAutoApply(LayerKind.MEANING, TRACK, CONFIG, "digest-2"));
        assertTrue(gate.mayAutoApply(LayerKind.MEANING, OTHER_TRACK, CONFIG, DIGEST));
    }

    @Test
    public void leavingTheTrackForgetsItsRestores() {
        gate.restore(LayerKind.MEANING, TRACK, CONFIG, DIGEST);
        gate.restore(LayerKind.MEANING, OTHER_TRACK, CONFIG, DIGEST);

        gate.forgetTrack(TRACK);

        assertTrue(gate.mayAutoApply(LayerKind.MEANING, TRACK, CONFIG, DIGEST));
        assertFalse("another track's Restore is untouched",
                gate.mayAutoApply(LayerKind.MEANING, OTHER_TRACK, CONFIG, DIGEST));
    }

    @Test
    public void askingForItAgainClearsTheRestore() {
        gate.restore(LayerKind.MEANING, TRACK, CONFIG, DIGEST);
        gate.clearSuppression(LayerKind.MEANING, TRACK, CONFIG, DIGEST);
        assertTrue(gate.mayAutoApply(LayerKind.MEANING, TRACK, CONFIG, DIGEST));
    }

    @Test
    public void switchingTheFeatureOffAndOnDoesNotStrandASuppression() {
        gate.restore(LayerKind.MEANING, TRACK, CONFIG, DIGEST);
        gate.setEnabled(false);
        gate.setEnabled(true);
        assertTrue("the first answer after switching back on must not be silently hidden",
                gate.mayAutoApply(LayerKind.MEANING, TRACK, CONFIG, DIGEST));
    }

    @Test
    public void suppressionIsReadableOnItsOwnSoALaneCanTellWhyItIsNotApplying() {
        gate.restore(LayerKind.SOUND, TRACK, CONFIG, DIGEST);
        assertTrue(gate.isSuppressed(LayerKind.SOUND, TRACK, CONFIG, DIGEST));
        assertFalse(gate.isSuppressed(LayerKind.MEANING, TRACK, CONFIG, DIGEST));
    }
}
