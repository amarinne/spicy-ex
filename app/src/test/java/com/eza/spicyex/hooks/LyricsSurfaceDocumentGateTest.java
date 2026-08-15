package com.eza.spicyex.hooks;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LyricsSurfaceDocumentGateTest {
    @Test
    public void laterRequestSupersedesPendingReplayAndObserverUpgradeWins() {
        LyricsSurfaceDocumentGate gate = new LyricsSurfaceDocumentGate();
        gate.start();

        LyricsSurfaceDocumentGate.Candidate replay = gate.offer("track-a");
        LyricsSurfaceDocumentGate.Candidate request = gate.offer("track-a");

        assertFalse(gate.accepts(replay, "track-a"));
        assertTrue(gate.accepts(request, "track-a"));

        LyricsSurfaceDocumentGate.Candidate observerUpgrade = gate.offer("track-a");

        assertFalse(gate.accepts(request, "track-a"));
        assertTrue(gate.accepts(observerUpgrade, "track-a"));
    }

    @Test
    public void trackChangeAndTeardownRejectPendingCandidate() {
        LyricsSurfaceDocumentGate gate = new LyricsSurfaceDocumentGate();
        gate.start();
        LyricsSurfaceDocumentGate.Candidate candidate = gate.offer("track-a");

        assertFalse(gate.accepts(candidate, "track-b"));

        gate.stop();

        assertFalse(gate.accepts(candidate, "track-a"));
    }
}
