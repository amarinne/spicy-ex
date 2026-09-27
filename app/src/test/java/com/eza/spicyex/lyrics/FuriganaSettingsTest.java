package com.eza.spicyex.lyrics;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class FuriganaSettingsTest {
    @Test
    public void defaultSettingsReproduceHardcodedValues() {
        FuriganaText.applySettings(59, 100);
        assertEquals(0xFF969696, FuriganaText.rubyColor());
        assertEquals(1.0f, FuriganaText.gapRatioScale(), 0.001f);
    }

    @Test
    public void applySettingsScalesBrightnessAndGap() {
        FuriganaText.applySettings(100, 150);
        assertEquals(0xFFFFFFFF, FuriganaText.rubyColor());
        assertEquals(1.5f, FuriganaText.gapRatioScale(), 0.001f);

        FuriganaText.applySettings(0, 40);
        assertEquals(0xFF000000, FuriganaText.rubyColor());
        assertEquals(0.4f, FuriganaText.gapRatioScale(), 0.001f);

        // Reset back to defaults
        FuriganaText.applySettings(59, 100);
    }
}
