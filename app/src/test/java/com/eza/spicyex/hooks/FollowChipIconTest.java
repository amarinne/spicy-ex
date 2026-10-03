package com.eza.spicyex.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.eza.spicyex.Settings;
import com.eza.spicyex.settings.PanelPolicy;
import com.eza.spicyex.settings.PanelSnapshot;

import org.junit.Test;

import java.util.Arrays;

/** Follow chip icon choice: stable stored values, adaptive direction, editor-only policy. */
public class FollowChipIconTest {
    @Test
    public void iconDefaultsToAdaptiveArrow() {
        assertEquals("Adaptive arrow", Settings.FOLLOW_CHIP_ICON.defaultValue);
        assertEquals(Arrays.asList("Adaptive arrow", "Static arrow", "Waveform"),
                Settings.FOLLOW_CHIP_ICON.allowedValues);
        assertEquals("Adaptive arrow", Settings.FOLLOW_CHIP_ICON.coerce("bogus"));
        assertEquals("Waveform", Settings.FOLLOW_CHIP_ICON.coerce("Waveform"));
        assertEquals(Settings.INTERNAL, Settings.FOLLOW_CHIP_ICON.section);
    }

    @Test
    public void adaptivePointsDownWhenViewportIsAboveTarget() {
        // Viewport above the desired target: the song is below, so the arrow points down.
        assertFalse(LyricsJumpToCurrentController.shouldPointUp(0, 500));
        assertFalse(LyricsJumpToCurrentController.shouldPointUp(100, 102));
        assertFalse(LyricsJumpToCurrentController.shouldPointUp(200, 200));
    }

    @Test
    public void adaptivePointsUpWhenViewportIsBelowTarget() {
        assertTrue(LyricsJumpToCurrentController.shouldPointUp(500, 0));
        assertTrue(LyricsJumpToCurrentController.shouldPointUp(105, 100));
    }

    @Test
    public void arrowGlyphsReuseDownArrowAndUpPair() {
        assertEquals("↓", LyricsJumpToCurrentController.arrowGlyph(false));
        assertEquals("↑", LyricsJumpToCurrentController.arrowGlyph(true));
    }

    @Test
    public void iconKindHelpers() {
        assertTrue(LyricsJumpToCurrentController.isAdaptiveIcon("Adaptive arrow"));
        assertFalse(LyricsJumpToCurrentController.isAdaptiveIcon("Static arrow"));
        assertFalse(LyricsJumpToCurrentController.isAdaptiveIcon("Waveform"));
        assertTrue(LyricsJumpToCurrentController.isWaveformIcon("Waveform"));
        assertFalse(LyricsJumpToCurrentController.isWaveformIcon("Adaptive arrow"));
    }

    @Test
    public void iconStaysOutOfTheNormalSettingsPanel() {
        PanelSnapshot full = PanelSnapshot.builder().allCapabilities().build();
        assertFalse(PanelPolicy.shouldRender(Settings.FOLLOW_CHIP_ICON, full));
    }
}
