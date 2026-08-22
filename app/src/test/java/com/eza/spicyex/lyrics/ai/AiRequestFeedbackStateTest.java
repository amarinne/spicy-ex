package com.eza.spicyex.lyrics.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AiRequestFeedbackStateTest {
    @Test
    public void acceptedRequestShowsPendingBeforeSessionPublication() {
        AiRequestFeedbackState state = new AiRequestFeedbackState();

        state.started();

        assertTrue(state.isPending(false));
        assertEquals(AiRequestFeedbackState.Outcome.NONE,
                state.observe(true, false, ""));
        assertTrue(state.isPending(true));
    }

    @Test
    public void settlementReportsFailureOrMissingOutputInsteadOfEndingSilently() {
        AiRequestFeedbackState failed = new AiRequestFeedbackState();
        failed.started();
        assertEquals(AiRequestFeedbackState.Outcome.FAILED,
                failed.observe(false, false, "runtime_unavailable"));
        assertFalse(failed.isPending(false));

        AiRequestFeedbackState empty = new AiRequestFeedbackState();
        empty.started();
        assertEquals(AiRequestFeedbackState.Outcome.NO_OUTPUT,
                empty.observe(false, false, ""));
    }

    @Test
    public void successfulSettlementStopsOptimisticPending() {
        AiRequestFeedbackState state = new AiRequestFeedbackState();
        state.started();

        assertEquals(AiRequestFeedbackState.Outcome.COMPLETED,
                state.observe(false, true, ""));
        assertFalse(state.isPending(false));
    }
}
