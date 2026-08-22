package com.eza.spicyex.lyrics.ai;

import static org.junit.Assert.assertFalse;

import org.junit.After;
import org.junit.Test;

public class AiModelLiveStateTest {
    @After
    public void reset() {
        AiModelLiveState.invalidate();
    }

    @Test
    public void missingConfigurationNeverLooksLive() {
        assertFalse(AiModelLiveState.begin(null));
        assertFalse(AiModelLiveState.isLive(null));
    }
}
