package com.eza.spicyex.lyrics.ai;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class AiProviderCredentialScopeTest {
    @Test
    public void officialOpenAiAndCustomNeverShareCredentialStorage() {
        String official = AiSettings.credentialScopeFor(AiSettings.PROVIDER_OPENAI);
        String custom = AiSettings.credentialScopeFor(AiSettings.PROVIDER_CUSTOM);

        assertEquals("openai_official", official);
        assertEquals("custom", custom);
        assertNotEquals(official, custom);
        assertNotEquals(AiOpenAiProvider.ID, official);
    }

    @Test
    public void geminiKeepsItsExistingCredentialScope() {
        assertEquals("gemini", AiSettings.credentialScopeFor(AiSettings.PROVIDER_GEMINI));
    }
}
