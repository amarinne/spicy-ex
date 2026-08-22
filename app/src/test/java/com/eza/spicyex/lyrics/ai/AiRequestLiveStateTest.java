package com.eza.spicyex.lyrics.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.eza.spicyex.lyrics.session.LayerKind;

import org.junit.After;
import org.junit.Test;

public class AiRequestLiveStateTest {
    @After
    public void clear() {
        AiRequestLiveState.clearForTest();
    }

    @Test
    public void recordsActualAttemptsAndPreservesFailureAcrossRetry() {
        AiRequestLiveState.begin(LayerKind.SOUND, "doc", "run-1");
        AiRequestLiveState.attempt(LayerKind.SOUND, "doc", "run-1",
                "C0", 1, "{\"items\":[1]}");
        AiRequestLiveState.fail(LayerKind.SOUND, "doc", "run-1",
                "delivery_unknown", 503, "server");

        AiRequestLiveState.begin(LayerKind.SOUND, "doc", "run-2");
        AiRequestLiveState.attempt(LayerKind.SOUND, "doc", "run-2",
                "C0", 1, "{\"items\":[2]}");
        AiRequestLiveState.complete(LayerKind.SOUND, "doc", "run-2");

        AiRequestLiveState.Snapshot snapshot =
                AiRequestLiveState.snapshot(LayerKind.SOUND, "doc");
        assertEquals(AiRequestLiveState.Phase.COMPLETE, snapshot.current.phase);
        assertTrue(snapshot.current.payload.contains("{\"items\":[2]}"));
        assertTrue(snapshot.previousFailure.isFailure());
        assertEquals("delivery_unknown", snapshot.previousFailure.failureToken);
        assertEquals(503, snapshot.previousFailure.httpStatus);
        assertTrue(snapshot.previousFailure.payload.contains("{\"items\":[1]}"));
    }

    @Test
    public void staleRunCannotOverwriteCurrentMonitor() {
        AiRequestLiveState.begin(LayerKind.MEANING, "doc", "new");
        AiRequestLiveState.attempt(LayerKind.MEANING, "doc", "old",
                "C0", 1, "stale");

        AiRequestLiveState.Snapshot snapshot =
                AiRequestLiveState.snapshot(LayerKind.MEANING, "doc");
        assertEquals(AiRequestLiveState.Phase.PREPARING, snapshot.current.phase);
        assertFalse(snapshot.current.hasPayload());
    }
}
