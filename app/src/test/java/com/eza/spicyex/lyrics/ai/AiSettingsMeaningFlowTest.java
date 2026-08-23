package com.eza.spicyex.lyrics.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.eza.spicyex.lyrics.RomanizationOptions;
import com.eza.spicyex.lyrics.session.LayerConfigIds;

import org.junit.Test;

/**
 * The Meaning flow value space: what a stored string can mean, what token it contributes to layer
 * identity, and the guarantee that switching flow moves run/config identity without ever touching
 * Sound or paid-request identity.
 */
public final class AiSettingsMeaningFlowTest {

    @Test
    public void everyStoredValueMapsToItsFlow() {
        assertEquals(AiSettings.MeaningFlow.GOOGLE_PREVIEW,
                AiSettings.MeaningFlow.ofStoredValue("Google preview"));
        assertEquals(AiSettings.MeaningFlow.GOOGLE_DRAFT,
                AiSettings.MeaningFlow.ofStoredValue("Google draft"));
        assertEquals(AiSettings.MeaningFlow.AI_ONLY,
                AiSettings.MeaningFlow.ofStoredValue("AI only"));
    }

    /** The default and anything unrecognized read as Google draft, never as a guessed behavior. */
    @Test
    public void unrecognizedValuesFallBackToTheShippedDefault() {
        assertEquals(AiSettings.MeaningFlow.GOOGLE_DRAFT,
                AiSettings.MeaningFlow.ofStoredValue(null));
        assertEquals(AiSettings.MeaningFlow.GOOGLE_DRAFT,
                AiSettings.MeaningFlow.ofStoredValue(""));
        assertEquals(AiSettings.MeaningFlow.GOOGLE_DRAFT,
                AiSettings.MeaningFlow.ofStoredValue("google preview"));
        assertEquals(AiSettings.MeaningFlow.GOOGLE_DRAFT,
                AiSettings.MeaningFlow.ofStoredValue("Refine with AI"));
    }

    @Test
    public void storedConstantsMatchTheirFlowValues() {
        assertEquals("Google preview", AiSettings.TRANSLATION_PIPELINE_GOOGLE_PREVIEW);
        assertEquals("Google draft", AiSettings.TRANSLATION_PIPELINE_GOOGLE_DRAFT);
        assertEquals("AI only", AiSettings.TRANSLATION_PIPELINE_AI_ONLY);
    }

    @Test
    public void flowTokensAreStableAndDistinct() {
        for (AiSettings.MeaningFlow flow : AiSettings.MeaningFlow.values()) {
            assertEquals(flow.name().toLowerCase(java.util.Locale.ROOT), flow.configToken());
        }
        assertNotEquals(AiSettings.MeaningFlow.GOOGLE_PREVIEW.configToken(),
                AiSettings.MeaningFlow.GOOGLE_DRAFT.configToken());
        assertNotEquals(AiSettings.MeaningFlow.GOOGLE_DRAFT.configToken(),
                AiSettings.MeaningFlow.AI_ONLY.configToken());
        assertNotEquals(AiSettings.MeaningFlow.GOOGLE_PREVIEW.configToken(),
                AiSettings.MeaningFlow.AI_ONLY.configToken());
    }

    /** Switching flow retires runs and invalidates projected preliminary artifacts. */
    @Test
    public void changingFlowChangesMeaningLayerIdentity() {
        String base = LayerConfigIds.meaning(true, "google_unofficial", "en", "auto", "auto",
                "google_draft");
        assertEquals(base,
                LayerConfigIds.meaning(true, "google_unofficial", "en", "auto", "auto",
                        "google_draft"));
        assertNotEquals(base,
                LayerConfigIds.meaning(true, "google_unofficial", "en", "auto", "auto",
                        "google_preview"));
        assertNotEquals(base,
                LayerConfigIds.meaning(true, "google_unofficial", "en", "auto", "auto",
                        "ai_only"));
    }

    /** Sound identity has no flow input at all — the lanes stay independent. */
    @Test
    public void soundIdentityCarriesNoMeaningFlowInput() {
        String sound = LayerConfigIds.sound(true, RomanizationOptions.DEFAULTS.cacheKey(), "ja", 3);
        assertTrue("sound config is built from Sound inputs only",
                sound.startsWith("sound-v") && !sound.contains("flow="));
    }
}
