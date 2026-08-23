package com.eza.spicyex.lyrics.ai;

import com.eza.spicyex.Settings;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

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

    /**
     * A routing gateway is not "some custom endpoint": sharing the custom scope would hand an
     * OpenRouter key to whatever endpoint the owner typed next, which is the collision that
     * separating the official OpenAI scope already fixed once.
     */
    @Test
    public void openRouterKeepsItsOwnCredentialScope() {
        String router = AiSettings.credentialScopeFor(AiSettings.PROVIDER_OPENROUTER);

        assertEquals("openrouter", router);
        assertNotEquals(router, AiSettings.credentialScopeFor(AiSettings.PROVIDER_CUSTOM));
        assertNotEquals(router, AiSettings.credentialScopeFor(AiSettings.PROVIDER_OPENAI));
        assertNotEquals(router, AiSettings.credentialScopeFor(AiSettings.PROVIDER_GEMINI));
    }

    @Test
    public void deepSeekDirectKeepsItsOwnCredentialScope() {
        String deepSeek = AiSettings.credentialScopeFor(AiSettings.PROVIDER_DEEPSEEK);

        assertEquals("deepseek", deepSeek);
        assertNotEquals(deepSeek, AiSettings.credentialScopeFor(AiSettings.PROVIDER_CUSTOM));
        assertNotEquals(deepSeek, AiSettings.credentialScopeFor(AiSettings.PROVIDER_OPENROUTER));
        assertNotEquals(deepSeek, AiSettings.credentialScopeFor(AiSettings.PROVIDER_OPENAI));
        assertNotEquals(deepSeek, AiSettings.credentialScopeFor(AiSettings.PROVIDER_GEMINI));
    }

    @Test
    public void deepSeekIsAFirstClassProviderWithAllDocumentedReasoningModes() {
        assertTrue(Settings.AI_PROVIDER.allowedValues.contains(AiSettings.PROVIDER_DEEPSEEK));
        assertEquals("Low", Settings.AI_DEEPSEEK_REASONING.defaultValue);
        assertTrue(Settings.AI_DEEPSEEK_REASONING.allowedValues.contains("Off"));
        assertTrue(Settings.AI_DEEPSEEK_REASONING.allowedValues.contains("Low"));
        assertTrue(Settings.AI_DEEPSEEK_REASONING.allowedValues.contains("High"));
        assertTrue(Settings.AI_DEEPSEEK_REASONING.allowedValues.contains("Max"));
    }
}
