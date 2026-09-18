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
                "lyrics", "lyrics_sources", "now_playing", "lyrics_screen", "apple_music",
                "transliteration", "translation", "ai"), ids);
    }

    @Test
    public void rowIdIsThePreferenceKey() {
        assertEquals(Settings.TAP_SEEK_MODE.key,
                SettingsUiSchema.specOf(Settings.TAP_SEEK_MODE).rowId);
        assertEquals(Settings.CACHE_SIZE.key,
                SettingsUiSchema.specOf(Settings.CACHE_SIZE).rowId);
    }

    @Test
    public void kindsMatchRendererDispatch() {
        assertEquals(SettingUiSpec.RowKind.TOGGLE,
                SettingsUiSchema.specOf(Settings.TRANSLATION_ENABLED).kind);
        assertEquals(SettingUiSpec.RowKind.STEPPER,
                SettingsUiSchema.specOf(Settings.SYNC_OFFSET_MS).kind);
        assertEquals(SettingUiSpec.RowKind.SINGLE_SELECT,
                SettingsUiSchema.specOf(Settings.TAP_SEEK_MODE).kind);
        assertEquals(SettingUiSpec.RowKind.SINGLE_SELECT,
                SettingsUiSchema.specOf(Settings.UI_LANGUAGE).kind);
        assertEquals(CommitPolicy.CONFIRMING,
                SettingsUiSchema.specOf(Settings.TRANSLATION_TARGET).commitPolicy);
        assertEquals(CommitPolicy.DEBOUNCED,
                SettingsUiSchema.specOf(Settings.SYNC_OFFSET_MS).commitPolicy);
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
