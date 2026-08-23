package com.eza.spicyex.lyrics.ai;

import static org.junit.Assert.assertEquals;

import com.eza.spicyex.lyrics.session.LayerKind;

import org.junit.Test;

public class AiLayerPanelPolicyTest {
    @Test
    public void runningStateAlwaysBlocksFailureReviewAndComposer() {
        assertEquals(AiLayerPanelPolicy.Destination.RUNNING_STATUS,
                AiLayerPanelPolicy.destination(true, "rate_limited", true));
        assertEquals(AiLayerPanelPolicy.Destination.RUNNING_STATUS,
                AiLayerPanelPolicy.destination(true, "", false));
    }

    @Test
    public void settledStateRoutesFailureThenReviewThenComposer() {
        assertEquals(AiLayerPanelPolicy.Destination.FAILURE,
                AiLayerPanelPolicy.destination(false, "rate_limited", true));
        assertEquals(AiLayerPanelPolicy.Destination.REVIEW,
                AiLayerPanelPolicy.destination(false, "", true));
        assertEquals(AiLayerPanelPolicy.Destination.COMPOSER,
                AiLayerPanelPolicy.destination(false, "", false));
    }

    /**
     * A settled failure must reach the failure panel even while the document still reports the
     * layer busy — that panel carries the retry and the failed-attempt payload, and the
     * running-status dialog carries neither.
     */
    @org.junit.Test
    public void aSettledFailureIsNotTreatedAsARunInProgress() {
        AiRequestLiveState.clearForTest();
        AiRequestLiveState.begin(LayerKind.MEANING, "digest", "run-1");
        AiRequestLiveState.attempt(LayerKind.MEANING, "digest", "run-1", "C0", 1, "{}");
        AiRequestLiveState.fail(LayerKind.MEANING, "digest", "run-1", "truncated", 0, "");

        AiRequestLiveState.Snapshot settled =
                AiRequestLiveState.snapshot(LayerKind.MEANING, "digest");
        org.junit.Assert.assertTrue(settled.current.isFailure());

        boolean documentStillSaysBusy = true;
        boolean running = documentStillSaysBusy && !settled.current.isFailure();
        org.junit.Assert.assertEquals(AiLayerPanelPolicy.Destination.FAILURE,
                AiLayerPanelPolicy.destination(running, "truncated", false));
    }

    /** A retry after a failure is a run in progress again, and must not show the old failure. */
    @org.junit.Test
    public void aRetryAfterAFailureShowsRunningStatusAgain() {
        AiRequestLiveState.clearForTest();
        AiRequestLiveState.begin(LayerKind.MEANING, "digest", "run-1");
        AiRequestLiveState.fail(LayerKind.MEANING, "digest", "run-1", "truncated", 0, "");
        AiRequestLiveState.begin(LayerKind.MEANING, "digest", "run-2");

        AiRequestLiveState.Snapshot retrying =
                AiRequestLiveState.snapshot(LayerKind.MEANING, "digest");
        org.junit.Assert.assertFalse(retrying.current.isFailure());
        org.junit.Assert.assertTrue(retrying.previousFailure.isFailure());

        boolean running = true && !retrying.current.isFailure();
        org.junit.Assert.assertEquals(AiLayerPanelPolicy.Destination.RUNNING_STATUS,
                AiLayerPanelPolicy.destination(running, "truncated", false));
    }
}
