package com.eza.spicyex.lyrics.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.eza.spicyex.Settings;
import com.eza.spicyex.SettingsStore;
import com.eza.spicyex.testsupport.FakeAndroidContext;

import org.junit.Before;
import org.junit.Test;

/**
 * What a stored configuration means before any lane reads it: which provider's model belongs to
 * which provider, what an install that predates the flow setting is migrated to, and whether the
 * request carries a Google baseline.
 */
public final class AiSettingsSelectionBehaviorTest {

    private FakeAndroidContext context;
    private SettingsStore store;
    private AiSettings settings;

    @Before public void setUp() {
        context = new FakeAndroidContext();
        store = new SettingsStore(context);
        settings = new AiSettings(store, new AiCredentialStore(context, new PlainCipher()));
    }

    /** Selecting a provider must never inherit another provider's model. */
    @Test public void unchosenProviderNeverInheritsAnotherProvidersModel() {
        // An install that predates provider-scoped models wrote the shared key while the default
        // provider was selected; that value belongs to that provider alone.
        store.put(Settings.AI_MODEL, "legacy-gemini-model");
        store.put(Settings.AI_PROVIDER, AiSettings.PROVIDER_OPENAI);
        assertEquals("a provider with no model of its own reports none",
                "", settings.modelName());

        store.put(Settings.AI_PROVIDER, AiSettings.PROVIDER_GEMINI);
        assertEquals("the default provider may still read the pre-scoping value",
                "legacy-gemini-model", settings.modelName());

        store.put(Settings.AI_MODEL_OPENAI, "gpt-4o-mini");
        store.put(Settings.AI_PROVIDER, AiSettings.PROVIDER_OPENAI);
        assertEquals("a chosen model wins for its own provider",
                "gpt-4o-mini", settings.modelName());
    }

    /** Legacy boolean migration maps to draft/AI-only only; an old install never gets preview. */
    @Test public void legacyMigrationNeverSelectsPreview() {
        store.put(Settings.AI_TRANSLATION_REFINE_GOOGLE, Boolean.TRUE);
        assertEquals(AiSettings.MeaningFlow.GOOGLE_DRAFT, settings.meaningFlow());

        store.put(Settings.AI_TRANSLATION_REFINE_GOOGLE, Boolean.FALSE);
        assertEquals(AiSettings.MeaningFlow.AI_ONLY, settings.meaningFlow());

        store.put(Settings.AI_TRANSLATION_PIPELINE, AiSettings.TRANSLATION_PIPELINE_GOOGLE_PREVIEW);
        assertEquals(AiSettings.MeaningFlow.GOOGLE_PREVIEW, settings.meaningFlow());
        assertFalse(settings.meaningUsesGoogleBaseline());
    }

    /** The binary predicate survives for callers outside the lane and agrees with the flow. */
    @Test public void meaningUsesGoogleBaselineTracksTheFlow() {
        store.put(Settings.AI_TRANSLATION_PIPELINE, AiSettings.TRANSLATION_PIPELINE_GOOGLE_DRAFT);
        assertTrue(settings.meaningUsesGoogleBaseline());

        store.put(Settings.AI_TRANSLATION_PIPELINE, AiSettings.TRANSLATION_PIPELINE_AI_ONLY);
        assertFalse(settings.meaningUsesGoogleBaseline());

        store.put(Settings.AI_TRANSLATION_PIPELINE, AiSettings.TRANSLATION_PIPELINE_GOOGLE_PREVIEW);
        assertFalse(settings.meaningUsesGoogleBaseline());
    }

    static final class PlainCipher implements AiCredentialStore.Cipher {
        @Override public String encrypt(String plaintext) {
            return plaintext;
        }

        @Override public String decrypt(String ciphertext) {
            return ciphertext;
        }

        @Override public void clear() {
        }
    }
}
