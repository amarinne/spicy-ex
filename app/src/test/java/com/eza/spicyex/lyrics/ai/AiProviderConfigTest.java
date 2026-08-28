package com.eza.spicyex.lyrics.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.eza.spicyex.lyrics.session.LayerKind;

import org.junit.Test;

import java.util.Collections;

public class AiProviderConfigTest {
    private static AiModelDescriptor model(int outputLimit) {
        return new AiModelDescriptor("model", "1", AiContract.MAX_REQUEST_BYTES, outputLimit,
                Collections.singletonList("generateContent"));
    }

    @Test
    public void storedConfigurationIsValidBeforePerCallCopy() {
        AiProviderConfig config = new AiProviderConfig(LayerKind.MEANING, null, "1",
                model(4_096), "en", AiContract.PROMPT_VERSION, false);

        assertEquals(4_096, config.maxOutputTokens);
        assertEquals(4_096, config.callOutputTokens());
    }

    @Test
    public void perCallOutputCapStaysPositiveAndWithinModelLimit() {
        AiProviderConfig config = new AiProviderConfig(LayerKind.MEANING, null, "1",
                model(2_048), "en", AiContract.PROMPT_VERSION, false);

        assertEquals(2_048, config.forCall(false, 0).maxOutputTokens);
        assertEquals(1_024, config.forCall(false, 1_024).maxOutputTokens);
        assertEquals(1_024, config.forCall(false, 1_024).callOutputTokens());
        assertTrue(config.forCall(false, Integer.MAX_VALUE).maxOutputTokens > 0);
    }
}
