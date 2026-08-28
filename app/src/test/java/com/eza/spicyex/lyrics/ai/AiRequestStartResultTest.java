package com.eza.spicyex.lyrics.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AiRequestStartResultTest {
    @Test
    public void onlyStartedDispositionReportsStarted() {
        for (AiRequestStartResult result : AiRequestStartResult.values()) {
            assertEquals(result == AiRequestStartResult.STARTED, result.started());
            assertFalse(result.token.isEmpty());
        }
        assertTrue(AiRequestStartResult.STARTED.started());
    }
}
