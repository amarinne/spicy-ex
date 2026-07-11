package com.eza.spicyex;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class SettingsValueNormalizerTest {
    @Test
    public void textWeightFallsBackToMedium() {
        assertEquals("Regular", SettingsValueNormalizer.normalizeTextWeight("Regular"));
        assertEquals("Bold", SettingsValueNormalizer.normalizeTextWeight("Bold"));
        assertEquals("Medium", SettingsValueNormalizer.normalizeTextWeight("Heavy"));
        assertEquals("Medium", SettingsValueNormalizer.normalizeTextWeight(null));
    }

    @Test
    public void textSizeModesNormalizeAndMapToMultipliers() {
        assertEquals("normal", SettingsValueNormalizer.normalizeTextSizeMode("giant"));
        assertEquals(0.9f, SettingsValueNormalizer.textSizeMultiplierFor("small"), 0.0001f);
        assertEquals(1.0f, SettingsValueNormalizer.textSizeMultiplierFor("normal"), 0.0001f);
        assertEquals(1.2f, SettingsValueNormalizer.textSizeMultiplierFor("large"), 0.0001f);
        assertEquals(1.5f, SettingsValueNormalizer.textSizeMultiplierFor("xlarge"), 0.0001f);
    }

    @Test
    public void textSizeMultiplierLabelsMatchRuntimeValues() {
        assertEquals("0.9", SettingsValueNormalizer.textSizeMultiplierLabel("small"));
        assertEquals("1.0", SettingsValueNormalizer.textSizeMultiplierLabel("normal"));
        assertEquals("1.2", SettingsValueNormalizer.textSizeMultiplierLabel("large"));
        assertEquals("1.5", SettingsValueNormalizer.textSizeMultiplierLabel("xlarge"));
    }
}
