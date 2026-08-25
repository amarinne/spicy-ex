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
    public void reasoningIsRetainedThroughTheRunAndSurvivesSettling() {
        AiRequestLiveState.begin(LayerKind.MEANING, "doc", "run-1");
        AiRequestLiveState.attempt(LayerKind.MEANING, "doc", "run-1", "C0", 1, "{\"items\":[1]}");
        AiRequestLiveState.reasoning(LayerKind.MEANING, "doc", "run-1", "C0", 1,
                "line 2 reads as an ad-lib");
        AiRequestLiveState.complete(LayerKind.MEANING, "doc", "run-1");

        AiRequestLiveState.Snapshot snapshot =
                AiRequestLiveState.snapshot(LayerKind.MEANING, "doc");
        assertEquals(AiRequestLiveState.Phase.COMPLETE, snapshot.current.phase);
        assertTrue(snapshot.current.hasReasoning());
        assertTrue(snapshot.current.reasoning.contains("Attempt 1 \u00b7 C0"));
        assertTrue(snapshot.current.reasoning.contains("line 2 reads as an ad-lib"));
        assertTrue(snapshot.lastSettled.hasReasoning());
        // The wire payload and the trace are different texts and must not bleed into each other.
        assertFalse(snapshot.current.payload.contains("ad-lib"));
    }

    /** A run whose reasoning explains the failure is exactly the one that must keep it. */
    @Test
    public void aFailedRunKeepsTheTraceThatExplainsIt() {
        AiRequestLiveState.begin(LayerKind.SOUND, "doc", "run-1");
        AiRequestLiveState.attempt(LayerKind.SOUND, "doc", "run-1", "C0", 1, "body");
        AiRequestLiveState.reasoning(LayerKind.SOUND, "doc", "run-1", "C0", 1, "ran long");
        AiRequestLiveState.fail(LayerKind.SOUND, "doc", "run-1", "truncated", 0, "");

        AiRequestLiveState.begin(LayerKind.SOUND, "doc", "run-2");

        AiRequestLiveState.Snapshot snapshot =
                AiRequestLiveState.snapshot(LayerKind.SOUND, "doc");
        assertTrue(snapshot.previousFailure.isFailure());
        assertTrue(snapshot.previousFailure.reasoning.contains("ran long"));
        assertFalse(snapshot.current.hasReasoning());
    }

    @Test
    public void multipleAttemptsAccumulateUnderTheirOwnHeadings() {
        AiRequestLiveState.begin(LayerKind.MEANING, "doc", "run-1");
        AiRequestLiveState.reasoning(LayerKind.MEANING, "doc", "run-1", "C0", 1, "first pass");
        AiRequestLiveState.reasoning(LayerKind.MEANING, "doc", "run-1", "C1", 2, "second pass");

        String reasoning = AiRequestLiveState.snapshot(LayerKind.MEANING, "doc").current.reasoning;
        assertTrue(reasoning.contains("Attempt 1 \u00b7 C0"));
        assertTrue(reasoning.contains("first pass"));
        assertTrue(reasoning.indexOf("first pass") < reasoning.indexOf("second pass"));
    }

    @Test
    public void anEmptyTraceIsNotRecordedAsAnEmptyHeading() {
        AiRequestLiveState.begin(LayerKind.MEANING, "doc", "run-1");
        AiRequestLiveState.reasoning(LayerKind.MEANING, "doc", "run-1", "C0", 1, "   ");

        assertFalse(AiRequestLiveState.snapshot(LayerKind.MEANING, "doc").current.hasReasoning());
    }

    /** Process-local state must not grow without bound on a long thinking run. */
    @Test
    public void anOversizedTraceKeepsItsConclusionAndSaysWhatItDropped() {
        StringBuilder huge = new StringBuilder();
        while (huge.length() < 120_000) huge.append("thinking out loud. ");
        AiRequestLiveState.begin(LayerKind.MEANING, "doc", "run-1");
        AiRequestLiveState.reasoning(LayerKind.MEANING, "doc", "run-1", "C0", 1,
                huge + "CONCLUSION");

        String reasoning = AiRequestLiveState.snapshot(LayerKind.MEANING, "doc").current.reasoning;
        assertTrue(reasoning.length() < huge.length());
        assertTrue(reasoning.contains("earlier reasoning dropped"));
        assertTrue(reasoning.endsWith("CONCLUSION"));
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

    @Test
    public void diagnosticSnapshotRetainsSuccessWhileNextRunIsPreparing() {
        AiRequestLiveState.begin(LayerKind.MEANING, "doc", "complete");
        AiRequestLiveState.attempt(LayerKind.MEANING, "doc", "complete",
                "C0", 1, "successful payload");
        AiRequestLiveState.complete(LayerKind.MEANING, "doc", "complete");
        AiRequestLiveState.begin(LayerKind.MEANING, "doc", "preparing");

        AiRequestLiveState.Snapshot snapshot =
                AiRequestLiveState.diagnosticSnapshot(LayerKind.MEANING);
        assertEquals(AiRequestLiveState.Phase.PREPARING, snapshot.current.phase);
        assertEquals(AiRequestLiveState.Phase.COMPLETE, snapshot.lastSettled.phase);
        assertTrue(snapshot.lastSettled.payload.contains("successful payload"));

        AiRequestLiveState.fail(LayerKind.MEANING, "doc", "preparing",
                "executor_rejected", 0, "");
        snapshot = AiRequestLiveState.diagnosticSnapshot(LayerKind.MEANING);
        assertEquals(AiRequestLiveState.Phase.FAILED, snapshot.current.phase);
        assertEquals(AiRequestLiveState.Phase.COMPLETE, snapshot.lastSettled.phase);
    }
}
