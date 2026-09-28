package com.eza.spicyex.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.eza.spicyex.Settings;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class SettingsUiSchemaTest {
    @Test
    public void sectionOrderIsExplicit() {
        List<String> ids = new ArrayList<>();
        for (Settings.Section section : SettingsUiSchema.orderedSections()) ids.add(section.id);
        assertEquals(java.util.Arrays.asList(
                "lyrics", "lyrics_sources", "lyrics_screen", "apple_music",
                "transliteration", "translation", "ai", "pip"), ids);
    }

    @Test
    public void kindsMatchRendererDispatch() {
        assertEquals(SettingUiSpec.RowKind.TOGGLE,
                SettingsUiSchema.kindOf(Settings.TRANSLATION_ENABLED));
        assertEquals(SettingUiSpec.RowKind.STEPPER,
                SettingsUiSchema.kindOf(Settings.SYNC_OFFSET_MS));
        assertEquals(SettingUiSpec.RowKind.SINGLE_SELECT,
                SettingsUiSchema.kindOf(Settings.TAP_SEEK_MODE));
        assertEquals(SettingUiSpec.RowKind.SINGLE_SELECT,
                SettingsUiSchema.kindOf(Settings.UI_LANGUAGE));
    }

    @Test
    public void compositesStayExplicit() {
        assertTrue(SettingsUiSchema.isComposite(Settings.LYRICS_SOURCE_MODE));
        assertTrue(SettingsUiSchema.isComposite(Settings.LYRICS_SOURCE_OVERRIDE));
        assertTrue(SettingsUiSchema.isComposite(Settings.LYRICS_SOURCE_ORDER));
        assertTrue(SettingsUiSchema.isComposite(Settings.SPICY_MANUAL_TOKEN));
        assertFalse(SettingsUiSchema.isComposite(Settings.CACHE_SIZE));
        assertFalse(SettingsUiSchema.isComposite(Settings.AI_PROVIDER));
    }
}
