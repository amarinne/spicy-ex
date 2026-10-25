package com.eza.spicyex.hooks;

import com.eza.spicyex.lyrics.ResponseCreditOutro;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * T0.3: the now-playing live card draws a frame only while that frame can show something the last
 * one did not. The gate is the whole contract, so each clause is pinned here rather than in a device
 * run: the failure mode of a wrong gate is either a frozen card or an always-on ticker.
 */
public class NowPlayingFrameDemandTest {
    private static final long SUNG_END_MS = 60_000L;

    private static boolean demand(boolean playing, boolean shown, boolean timed, long positionMs) {
        return NowPlayingLyricController.frameDemandOpen(
                false, playing, shown, timed, positionMs, SUNG_END_MS);
    }

    @Test
    public void drawsThroughTheWholeSungSpan() {
        assertTrue(demand(true, true, true, 0L));
        assertTrue("mid-song", demand(true, true, true, SUNG_END_MS / 2));
        assertTrue("last row still washing", demand(true, true, true, SUNG_END_MS - 1L));
    }

    @Test
    public void stopsWhenPlaybackStopsAdvancing() {
        // A paused player does not extrapolate its position, so another frame is the same pixels.
        assertFalse(demand(false, true, true, SUNG_END_MS / 2));
    }

    @Test
    public void stopsWhenNeitherSurfaceIsOnScreen() {
        assertFalse(demand(true, false, true, SUNG_END_MS / 2));
    }

    @Test
    public void stopsWithoutATimedDocument() {
        // Placeholder (no lyrics), loading, or a static/unsynced document: nothing moves per frame.
        assertFalse(demand(true, true, false, SUNG_END_MS / 2));
    }

    @Test
    public void keepsDrawingOnlyUntilTheHoldWindowThatClearsTheLastRow() {
        // The row is cleared on the first frame past the last sung row, which lives inside the
        // credit-outro hold window; from there the card holds a settled frame.
        assertTrue(demand(true, true, true, SUNG_END_MS + ResponseCreditOutro.HOLD_MS - 1L));
        assertFalse(demand(true, true, true, SUNG_END_MS + ResponseCreditOutro.HOLD_MS));
    }

    @Test
    public void forcedWorkOutranksTheOtherGates() {
        // A canvas handoff or a bounded target refresh must settle even while paused or hidden.
        assertTrue(NowPlayingLyricController.frameDemandOpen(
                true, false, false, false,
                NowPlayingLyricController.NO_POSITION_MS, SUNG_END_MS));
    }

    @Test
    public void anUnreadPositionIsNotDemand() {
        assertFalse(demand(true, true, true, NowPlayingLyricController.NO_POSITION_MS));
    }

    @Test
    public void anUntimedDocumentNeverKeepsTheLoopOpen() {
        assertFalse(NowPlayingLyricController.frameDemandOpen(
                false, true, true, true, 0L, 0L));
    }
}
