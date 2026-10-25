package com.eza.spicyex.hooks;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LyricsSurfaceDocumentGateTest {
    @Test
    public void reverseWorkerCompletionCannotOverwriteNewerTrackOrSameTrackUpgrade() {
        LyricsSurfaceDocumentGate gate = new LyricsSurfaceDocumentGate();
        gate.start();
        LyricsSurfaceDocumentGate.Candidate oldTrack = gate.offer("track-a");
        LyricsSurfaceDocumentGate.Candidate replay = gate.offer("track-b");
        LyricsSurfaceDocumentGate.Candidate upgrade = gate.offer("track-b");
        List<String> committed = new ArrayList<>();
        // Two IO workers may finish in either order. These are the main posts they enqueue.
        Runnable oldPost = () -> { if (gate.accepts(oldTrack, "track-b")) committed.add("old"); };
        Runnable replayPost = () -> { if (gate.accepts(replay, "track-b")) committed.add("replay"); };
        Runnable upgradePost = () -> { if (gate.accepts(upgrade, "track-b")) committed.add("upgrade"); };

        upgradePost.run();
        oldPost.run();
        replayPost.run();

        org.junit.Assert.assertEquals(java.util.Collections.singletonList("upgrade"), committed);
    }

    @Test
    public void newerOfferRejectsResultAlreadyWaitingInMainQueueAndRestartRejectsOldLifecycle() {
        LyricsSurfaceDocumentGate gate = new LyricsSurfaceDocumentGate();
        gate.start();
        LyricsSurfaceDocumentGate.Candidate queued = gate.offer("track-a");
        Runnable queuedPost = () -> assertFalse(gate.accepts(queued, "track-a"));

        LyricsSurfaceDocumentGate.Candidate newer = gate.offer("track-a");
        queuedPost.run();
        assertTrue(gate.accepts(newer, "track-a"));
        gate.stop();
        gate.start();
        gate.offer("track-a");
        assertFalse(gate.accepts(newer, "track-a"));
    }

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

    @Test
    public void documentRetirementRejectsPendingSameTrackProjection() {
        LyricsSurfaceDocumentGate gate = new LyricsSurfaceDocumentGate();
        gate.start();
        LyricsSurfaceDocumentGate.Candidate candidate = gate.offer("track-a");

        gate.invalidate();

        assertFalse(gate.accepts(candidate, "track-a"));
    }
}
