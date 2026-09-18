package com.eza.spicyex.lyrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ArtGestureArbiterTest {
    private static final float SLOP = 8f;
    private static final long DOUBLE_TAP_MS = 300L;
    private static final float COMMIT = 32f;

    private long nowMs;
    private ArtGestureArbiter arbiter() {
        nowMs = 1_000L;
        return new ArtGestureArbiter(SLOP, DOUBLE_TAP_MS, COMMIT, () -> nowMs);
    }

    @Test
    public void firstTapRevealsOverlay() {
        ArtGestureArbiter arbiter = arbiter();
        assertEquals(ArtGestureArbiter.Output.NONE, arbiter.onDown(nowMs));
        assertEquals(ArtGestureArbiter.Output.REVEAL, arbiter.onUp());
    }

    @Test
    public void secondTapInsideWindowTogglesExactlyOnce() {
        ArtGestureArbiter arbiter = arbiter();
        arbiter.onDown(nowMs);
        assertEquals(ArtGestureArbiter.Output.REVEAL, arbiter.onUp());
        nowMs += 120L;
        assertEquals(ArtGestureArbiter.Output.NONE, arbiter.onDown(nowMs));
        assertEquals(ArtGestureArbiter.Output.TOGGLE, arbiter.onUp());
        // Window consumed: a third tap starts a fresh reveal cycle.
        nowMs += 120L;
        arbiter.onDown(nowMs);
        assertEquals(ArtGestureArbiter.Output.REVEAL, arbiter.onUp());
    }

    @Test
    public void lateSecondTapRevealsInsteadOfToggling() {
        ArtGestureArbiter arbiter = arbiter();
        arbiter.onDown(nowMs);
        arbiter.onUp();
        nowMs += DOUBLE_TAP_MS + 1L;
        arbiter.onDown(nowMs);
        assertEquals(ArtGestureArbiter.Output.REVEAL, arbiter.onUp());
    }

    @Test
    public void horizontalDragCancelsTapAndCommitsNextPastThreshold() {
        ArtGestureArbiter arbiter = arbiter();
        arbiter.onDown(nowMs);
        assertEquals(ArtGestureArbiter.Output.DRAG_UPDATE, arbiter.onMove(-40f, 2f));
        assertEquals(-40f, arbiter.dragDxPx(), 0.001f);
        assertEquals(ArtGestureArbiter.Output.COMMIT_NEXT, arbiter.onUp());
    }

    @Test
    public void dragRightCommitsPreviousAndShortDragSpringsBack() {
        ArtGestureArbiter arbiter = arbiter();
        arbiter.onDown(nowMs);
        arbiter.onMove(40f, 1f);
        assertEquals(ArtGestureArbiter.Output.COMMIT_PREV, arbiter.onUp());

        arbiter.onDown(nowMs);
        arbiter.onMove(10f, 0f);
        assertEquals(ArtGestureArbiter.Output.SPRING_BACK, arbiter.onUp());
    }

    @Test
    public void verticalMotionCancelsGestureSilently() {
        ArtGestureArbiter arbiter = arbiter();
        arbiter.onDown(nowMs);
        assertEquals(ArtGestureArbiter.Output.NONE, arbiter.onMove(2f, 30f));
        assertEquals(ArtGestureArbiter.Output.NONE, arbiter.onUp());
    }

    @Test
    public void doubleTapCandidateTracksWindow() {
        ArtGestureArbiter arbiter = arbiter();
        assertFalse(arbiter.isDoubleTapCandidate(nowMs));
        arbiter.onDown(nowMs);
        assertEquals(ArtGestureArbiter.Output.REVEAL, arbiter.onUp());
        nowMs += 120L;
        assertTrue(arbiter.isDoubleTapCandidate(nowMs));
        nowMs += DOUBLE_TAP_MS;
        assertFalse(arbiter.isDoubleTapCandidate(nowMs));
    }

    @Test
    public void resetClearsDoubleTapArming() {
        ArtGestureArbiter arbiter = arbiter();
        arbiter.onDown(nowMs);
        arbiter.onUp();
        nowMs += 120L;
        assertEquals(ArtGestureArbiter.Output.RESET, arbiter.reset());
        assertFalse(arbiter.isDoubleTapCandidate(nowMs));
        arbiter.onDown(nowMs);
        assertEquals(ArtGestureArbiter.Output.REVEAL, arbiter.onUp());
    }

    @Test
    public void cancelAndResetSnapBack() {
        ArtGestureArbiter arbiter = arbiter();
        arbiter.onDown(nowMs);
        arbiter.onMove(-50f, 0f);
        assertEquals(ArtGestureArbiter.Output.RESET, arbiter.onCancel());
        assertEquals(0f, arbiter.dragDxPx(), 0.001f);
        assertEquals(ArtGestureArbiter.Output.NONE, arbiter.onUp());
    }
}
