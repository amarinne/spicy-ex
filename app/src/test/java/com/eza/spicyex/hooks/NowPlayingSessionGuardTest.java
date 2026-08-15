package com.eza.spicyex.hooks;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class NowPlayingSessionGuardTest {
    @Test
    public void rejectsLatePreviousTrackDocument() {
        assertFalse(NowPlayingSessionGuard.matchesCurrentTrack("A", "B", "B"));
        assertFalse(NowPlayingSessionGuard.matchesCurrentTrack("A", "A", "B"));
    }

    @Test
    public void acceptsCurrentTrackIncludingInitialReplayBeforeCardPoll() {
        assertTrue(NowPlayingSessionGuard.matchesCurrentTrack("A", "A", ""));
        assertTrue(NowPlayingSessionGuard.matchesCurrentTrack("A", "A", "A"));
    }

    @Test
    public void projectionGuardRejectsStopUpgradeGenerationAndTrackChanges() {
        assertTrue(NowPlayingSessionGuard.projectionIsStale(
                false, 1, 1, 2, 2, "A", "A"));
        assertTrue(NowPlayingSessionGuard.projectionIsStale(
                true, 1, 2, 2, 2, "A", "A"));
        assertTrue(NowPlayingSessionGuard.projectionIsStale(
                true, 1, 1, 2, 3, "A", "A"));
        assertTrue(NowPlayingSessionGuard.projectionIsStale(
                true, 1, 1, 2, 2, "A", "B"));
        assertFalse(NowPlayingSessionGuard.projectionIsStale(
                true, 1, 1, 2, 2, "A", "A"));
    }
}
