package com.eza.spicyex;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SettingsAiVisibilityTest {
    @Test
    public void masterSwitchIsVisibleWhenAiIsOff() {
        assertTrue(SettingsPanel.aiSettingVisible(Settings.AI_ENABLED, false));
    }

    @Test
    public void allOtherAiSettingsNestUnderMasterSwitch() {
        assertFalse(SettingsPanel.aiSettingVisible(Settings.AI_PROVIDER, false));
        assertFalse(SettingsPanel.aiSettingVisible(Settings.AI_TRANSLATION_MODE, false));
        assertFalse(SettingsPanel.aiSettingVisible(Settings.AI_TRANSLATION_PIPELINE, false));
        assertFalse(SettingsPanel.aiSettingVisible(Settings.AI_PRONUNCIATION_MODE, false));
        assertFalse(SettingsPanel.aiSettingVisible(Settings.AI_PRONUNCIATION_SOURCE, false));
        assertFalse(SettingsPanel.aiSettingVisible(Settings.AI_BUTTON_BEHAVIOR, false));
    }

    @Test
    public void allAiSettingsAreVisibleWhenEnabled() {
        assertTrue(SettingsPanel.aiSettingVisible(Settings.AI_ENABLED, true));
        assertTrue(SettingsPanel.aiSettingVisible(Settings.AI_PROVIDER, true));
        assertTrue(SettingsPanel.aiSettingVisible(Settings.AI_BUTTON_BEHAVIOR, true));
    }
}
