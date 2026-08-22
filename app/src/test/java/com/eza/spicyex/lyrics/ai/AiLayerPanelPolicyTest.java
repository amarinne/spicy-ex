package com.eza.spicyex.lyrics.ai;

import static org.junit.Assert.assertEquals;

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
}
