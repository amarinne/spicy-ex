package com.eza.spicyex.settings.panel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.eza.spicyex.Settings;
import com.eza.spicyex.settings.registry.SettingsSnapshot;
import com.eza.spicyex.settings.registry.UiContext;

import org.junit.Test;

import java.util.Collections;

/**
 * The panel's own policy: availability, commit timing, and rebuild scope.
 *
 * <p>Row visibility is not here: it lives in the control registry, which
 * {@code PanelVisibilityTest} covers.
 */
public class PanelPolicyTest {
    private static PanelStrings strings() {
        return new PanelStrings.MapStrings(Collections.emptyMap());
    }

    private static UiContext context(Object... settings) {
        java.util.Map<String, Object> values = new java.util.LinkedHashMap<>();
        for (int i = 0; i + 1 < settings.length; i += 2) {
            values.put(((Settings.Setting<?>) settings[i]).key, settings[i + 1]);
        }
        return new UiContext(false, false, false, new SettingsSnapshot(values), true);
    }

    @Test
    public void noRowIsDimmed() {
        assertFalse(PanelPolicy.unavailable(Settings.TRANSLATION_ENABLED));
        assertFalse(PanelPolicy.unavailable(Settings.TRANSLITERATION_ENABLED));
        assertFalse(PanelPolicy.unavailable(Settings.AI_ENABLED));
    }

    // --- Availability ---

    @Test
    public void liveCardSecondaryModeExplainsItsRequirements() {
        assertEquals("Enable transliteration", PanelPolicy.optionUnavailableReason(
                (Settings.StringSetting) Settings.LIVE_CARD_SECONDARY_MODE,
                "Both", context(), strings()));
        assertEquals("Enable translation", PanelPolicy.optionUnavailableReason(
                (Settings.StringSetting) Settings.LIVE_CARD_SECONDARY_MODE,
                "Both", context(Settings.TRANSLITERATION_ENABLED, true), strings()));
        assertEquals("", PanelPolicy.optionUnavailableReason(
                (Settings.StringSetting) Settings.LIVE_CARD_SECONDARY_MODE,
                "Transliteration", context(Settings.TRANSLITERATION_ENABLED, true), strings()));
        assertEquals("", PanelPolicy.optionUnavailableReason(
                (Settings.StringSetting) Settings.LIVE_CARD_SECONDARY_MODE,
                "Both", context(Settings.TRANSLITERATION_ENABLED, true,
                        Settings.TRANSLATION_ENABLED, true), strings()));
    }

    @Test
    public void backgroundStyleAlwaysSelectable() {
        assertEquals("", PanelPolicy.optionUnavailableReason(
                (Settings.StringSetting) Settings.BACKGROUND_STYLE,
                "Animated texture", context(), strings()));
        assertEquals("", PanelPolicy.optionUnavailableReason(
                (Settings.StringSetting) Settings.BACKGROUND_STYLE,
                "Gradient", context(), strings()));
    }

    // --- Commit policy / rebuild scope ---

    @Test
    public void commitPolicyMatchesCurrentBehavior() {
        assertEquals(CommitPolicy.CONFIRMING,
                PanelPolicy.commitPolicyFor(Settings.TRANSLATION_TARGET));
        assertEquals(CommitPolicy.DEBOUNCED,
                PanelPolicy.commitPolicyFor(Settings.SYNC_OFFSET_MS));
        assertEquals(CommitPolicy.IMMEDIATE,
                PanelPolicy.commitPolicyFor(Settings.TAP_SEEK_MODE));
        assertEquals(CommitPolicy.IMMEDIATE,
                PanelPolicy.commitPolicyFor(Settings.TRANSLATION_ENABLED));
    }

    @Test
    public void rebuildScopeCoversGatingToggles() {
        assertTrue(PanelPolicy.shouldRebuildSectionAfterChange(Settings.TRANSLATION_ENABLED));
        assertTrue(PanelPolicy.shouldRebuildSectionAfterChange(Settings.LYRICS_SOURCE_MODE));
        assertTrue(PanelPolicy.shouldRebuildSectionAfterChange(Settings.JAPANESE_READING_MODE));
        // UI language takes the full rebuild path (every label changes), not the section path.
        assertFalse(PanelPolicy.shouldRebuildSectionAfterChange(Settings.UI_LANGUAGE));
        assertFalse(PanelPolicy.shouldRebuildSectionAfterChange(Settings.TAP_SEEK_MODE));
        assertFalse(PanelPolicy.shouldRebuildSectionAfterChange(Settings.CACHE_SIZE));
    }

    /**
     * A stray tap in an open language list must not change the interface language. Every
     * language-class picker commits only on an explicit Save; UI language is the one whose
     * accidental commitment re-renders the whole panel and reads as "the UI changed language on
     * its own".
     */
    @Test
    public void languageClassSelectorsCommitOnlyOnSave() {
        Settings.Setting<?>[] languageSelectors = {
                Settings.UI_LANGUAGE,
                Settings.TRANSLATION_TARGET,
                Settings.JAPANESE_READING_MODE,
                Settings.CHINESE_MODE,
                Settings.CHINESE_CHARACTER_FORM,
                Settings.KOREAN_ROMANIZATION,
                Settings.CYRILLIC_MODE
        };
        for (Settings.Setting<?> setting : languageSelectors) {
            assertTrue(setting.key + " must not commit on a stray tap",
                    PanelPolicy.isConfirmingSelector(setting));
            assertEquals(setting.key + " must be CONFIRMING",
                    CommitPolicy.CONFIRMING, PanelPolicy.commitPolicyFor(setting));
        }
    }

    @Test
    public void ordinarySelectorsStillCommitImmediately() {
        assertFalse(PanelPolicy.isConfirmingSelector(Settings.CACHE_SIZE));
        assertEquals(CommitPolicy.IMMEDIATE, PanelPolicy.commitPolicyFor(Settings.CACHE_SIZE));
        assertEquals(CommitPolicy.IMMEDIATE, PanelPolicy.commitPolicyFor(Settings.TAP_SEEK_MODE));
        assertEquals(CommitPolicy.DEBOUNCED, PanelPolicy.commitPolicyFor(Settings.SYNC_OFFSET_MS));
    }
}
