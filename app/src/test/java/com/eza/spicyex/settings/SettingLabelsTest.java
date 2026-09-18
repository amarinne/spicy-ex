package com.eza.spicyex.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.eza.spicyex.Settings;

import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class SettingLabelsTest {
    @Test
    public void lineSpacingMultiplierTable() {
        assertEquals("0.8", SettingLabels.multiplierFor("line_spacing", "compact"));
        assertEquals("1.1", SettingLabels.multiplierFor("line_spacing", "default"));
        assertEquals("1.5", SettingLabels.multiplierFor("line_spacing", "spacious"));
        assertEquals("2.0", SettingLabels.multiplierFor("line_spacing", "more"));
        assertEquals("2.5", SettingLabels.multiplierFor("line_spacing", "max"));
        assertNull(SettingLabels.multiplierFor("line_spacing", "custom"));
    }

    @Test
    public void textSizeMultiplierLabels() {
        assertEquals("0.9", SettingLabels.multiplierFor("lyrics_text_size", "small"));
        assertEquals("1.0", SettingLabels.multiplierFor("lyrics_text_size", "normal"));
        assertEquals("1.2", SettingLabels.multiplierFor("lyrics_text_size", "large"));
        assertEquals("1.5", SettingLabels.multiplierFor("lyrics_text_size", "xlarge"));
        assertNull(SettingLabels.multiplierFor("lyrics_text_size", "custom"));
        assertEquals("1.2", SettingLabels.multiplierFor("lyrics_live_card_text_size", "large"));
        assertNull(SettingLabels.multiplierFor("lyric_tap_seek_mode", "Double tap"));
    }

    @Test
    public void stepperFormatting() {
        assertEquals("35%", SettingLabels.formatStepper(Settings.EXTRA_DARK_BACKGROUND, 35));
        assertEquals("×1.50", SettingLabels.formatStepper(Settings.LYRICS_TEXT_SIZE_CUSTOM, 150));
        assertEquals("×1.00", SettingLabels.formatStepper(Settings.LINE_SPACING_CUSTOM, 100));
        assertEquals("0.0s", SettingLabels.formatStepper(Settings.SYNC_OFFSET_MS, 0));
        assertEquals("+1.5s", SettingLabels.formatStepper(Settings.SYNC_OFFSET_MS, 1500));
        assertEquals("-2.0s", SettingLabels.formatStepper(Settings.SYNC_OFFSET_MS, -2000));
    }

    @Test
    public void interludePreviewGlyphs() {
        assertEquals("• • •", SettingLabels.optionPreview("lyric_interlude_icon", "dots"));
        assertEquals("♪", SettingLabels.optionPreview("lyric_interlude_icon", "note"));
        assertEquals("", SettingLabels.optionPreview("lyric_interlude_icon", "other"));
        assertEquals("", SettingLabels.optionPreview("lyrics_weight", "Bold"));
    }

    @Test
    public void cacheUsageSummaryUsesFallbackPattern() {
        PanelStrings strings = new PanelStrings.MapStrings(Collections.emptyMap());
        assertEquals("128 MB · 12 MB used",
                SettingLabels.cacheUsageSummary(strings, "128 MB", "12 MB"));
    }

    @Test
    public void cacheUsageSummaryPrefersLocalePattern() {
        Map<String, String> localized = new HashMap<>();
        localized.put("settings_cache_size_usage_suffix", "%2$s / %1$s");
        PanelStrings strings = new PanelStrings.MapStrings(localized);
        assertEquals("12 MB / 128 MB",
                SettingLabels.cacheUsageSummary(strings, "128 MB", "12 MB"));
    }
}
