package com.eza.spicyex.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SpicyBridgeReplayStateTest {
    @Test
    public void replaysLatestPayloadOnceAfterConnectionOpens() {
        SpicyBridgeReplayState state = new SpicyBridgeReplayState();
        long revision = state.retainPayload();

        assertEquals(revision, state.pendingRevision());
        state.markPublished(revision);
        assertEquals(0L, state.pendingRevision());

        state.onConnectionOpened();
        assertEquals(revision, state.pendingRevision());
        state.markPublished(revision);
        assertEquals(0L, state.pendingRevision());
    }

    @Test
    public void staleCompletionCannotHideReplacementPayload() {
        SpicyBridgeReplayState state = new SpicyBridgeReplayState();
        long staleRevision = state.retainPayload();
        long currentRevision = state.retainPayload();

        state.markPublished(staleRevision);

        assertEquals(currentRevision, state.pendingRevision());
    }

    @Test
    public void clearPreventsReplayOnLaterConnection() {
        SpicyBridgeReplayState state = new SpicyBridgeReplayState();
        state.retainPayload();
        state.clearPayload();

        state.onConnectionOpened();

        assertEquals(0L, state.pendingRevision());
    }

    @Test
    public void stateAndDocumentReplayTrackConnectionsIndependently() {
        SpicyBridgeReplayState state = new SpicyBridgeReplayState();
        SpicyBridgeReplayState document = new SpicyBridgeReplayState();
        long stateRevision = state.retainPayload();
        long documentRevision = document.retainPayload();
        state.markPublished(stateRevision);
        document.markPublished(documentRevision);

        state.onConnectionOpened();
        document.onConnectionOpened();

        assertEquals(stateRevision, state.pendingRevision());
        assertEquals(documentRevision, document.pendingRevision());
    }

    @Test
    public void disconnectedBoundServiceWaitsForAndroidAutomaticReconnect() {
        assertTrue(SpicyBridgeReplayState.shouldAwaitAutomaticReconnect(true));
        assertFalse(SpicyBridgeReplayState.shouldAwaitAutomaticReconnect(false));
    }
}
