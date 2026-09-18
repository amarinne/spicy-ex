package com.eza.spicyex.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.eza.spicyex.Settings;

import org.junit.Test;

import java.util.Collections;

/** Gating matrix for the settings panel; no Android views. Migrated from SettingsAiVisibilityTest. */
public class PanelPolicyTest {
    private static PanelSnapshot full() {
        return PanelSnapshot.builder().allCapabilities().build();
    }

    private static PanelStrings strings() {
        return new PanelStrings.MapStrings(Collections.emptyMap());
    }

    // --- AI nesting (migrated) ---

    @Test
    public void masterSwitchIsVisibleWhenAiIsOff() {
        assertTrue(PanelPolicy.aiSettingVisible(Settings.AI_ENABLED, false));
    }

    @Test
    public void allOtherAiSettingsNestUnderMasterSwitch() {
        assertFalse(PanelPolicy.aiSettingVisible(Settings.AI_PROVIDER, false));
        assertFalse(PanelPolicy.aiSettingVisible(Settings.AI_TRANSLATION_MODE, false));
        assertFalse(PanelPolicy.aiSettingVisible(Settings.AI_TRANSLATION_PIPELINE, false));
        assertFalse(PanelPolicy.aiSettingVisible(Settings.AI_PRONUNCIATION_MODE, false));
        assertFalse(PanelPolicy.aiSettingVisible(Settings.AI_PRONUNCIATION_SOURCE, false));
        assertFalse(PanelPolicy.aiSettingVisible(Settings.AI_BUTTON_BEHAVIOR, false));
    }

    @Test
    public void allAiSettingsAreVisibleWhenEnabled() {
        assertTrue(PanelPolicy.aiSettingVisible(Settings.AI_ENABLED, true));
        assertTrue(PanelPolicy.aiSettingVisible(Settings.AI_PROVIDER, true));
        assertTrue(PanelPolicy.aiSettingVisible(Settings.AI_BUTTON_BEHAVIOR, true));
    }

    @Test
    public void aiSectionHiddenWhenFamilyNotOffered() {
        PanelSnapshot lite = PanelSnapshot.builder().build();
        assertFalse(PanelPolicy.shouldRender(Settings.AI_ENABLED, lite));
        assertFalse(PanelPolicy.shouldRender(Settings.AI_PROVIDER, lite));
    }

    @Test
    public void aiFamilyNestsUnderMasterSwitch() {
        PanelSnapshot off = PanelSnapshot.builder().allCapabilities()
                .put(Settings.AI_ENABLED, false).build();
        assertTrue(PanelPolicy.shouldRender(Settings.AI_ENABLED, off));
        assertFalse(PanelPolicy.shouldRender(Settings.AI_PROVIDER, off));
        PanelSnapshot on = PanelSnapshot.builder().allCapabilities()
                .put(Settings.AI_ENABLED, true).build();
        assertTrue(PanelPolicy.shouldRender(Settings.AI_PROVIDER, on));
    }

    @Test
    public void deepseekReasoningNeedsDeepseekProviderAndEnabled() {
        PanelSnapshot wrong = PanelSnapshot.builder().allCapabilities()
                .put(Settings.AI_ENABLED, true)
                .put(Settings.AI_PROVIDER, "gemini").build();
        assertFalse(PanelPolicy.shouldRender(Settings.AI_DEEPSEEK_REASONING, wrong));
        PanelSnapshot right = PanelSnapshot.builder().allCapabilities()
                .put(Settings.AI_ENABLED, true)
                .put(Settings.AI_PROVIDER, "deepseek").build();
        assertTrue(PanelPolicy.shouldRender(Settings.AI_DEEPSEEK_REASONING, right));
    }

    // --- Translation / transliteration ---

    @Test
    public void translationRowsNeedCapabilityAndMasterSwitch() {
        PanelSnapshot noCapability = PanelSnapshot.builder().allCapabilities()
                .translationAvailable(false)
                .put(Settings.TRANSLATION_ENABLED, true).build();
        assertFalse(PanelPolicy.shouldRender(Settings.TRANSLATION_TARGET, noCapability));
        PanelSnapshot disabled = full();
        assertFalse(PanelPolicy.shouldRender(Settings.TRANSLATION_TARGET, disabled));
        PanelSnapshot on = PanelSnapshot.builder().allCapabilities()
                .put(Settings.TRANSLATION_ENABLED, true).build();
        assertTrue(PanelPolicy.shouldRender(Settings.TRANSLATION_TARGET, on));
        assertTrue(PanelPolicy.shouldRender(Settings.TRANSLATION_BRIGHTNESS, on));
    }

    @Test
    public void readingFamilyNeedsCapabilityAndMasterSwitch() {
        PanelSnapshot on = PanelSnapshot.builder().allCapabilities()
                .put(Settings.TRANSLITERATION_ENABLED, true).build();
        assertTrue(PanelPolicy.shouldRender(Settings.JAPANESE_READING_MODE, on));
        assertTrue(PanelPolicy.shouldRender(Settings.CHINESE_MODE, on));
        assertTrue(PanelPolicy.shouldRender(Settings.KOREAN_ROMANIZATION, on));
        assertTrue(PanelPolicy.shouldRender(Settings.CHINESE_TONES, on));
        assertTrue(PanelPolicy.shouldRender(Settings.CYRILLIC_MODE, on));
        assertTrue(PanelPolicy.shouldRender(Settings.CYRILLIC_KEEP_SIGNS, on));
        assertTrue(PanelPolicy.shouldRender(Settings.ALIGNED_PER_WORD_ROMAJI, on));
        PanelSnapshot disabled = full();
        assertFalse(PanelPolicy.shouldRender(Settings.JAPANESE_READING_MODE, disabled));
        PanelSnapshot lite = PanelSnapshot.builder()
                .put(Settings.TRANSLITERATION_ENABLED, true).build();
        assertFalse(PanelPolicy.shouldRender(Settings.JAPANESE_READING_MODE, lite));
    }

    // --- Background / animation / custom sizes ---

    @Test
    public void forceDarkNeedsAnimatedBackgroundAndTextureStyle() {
        PanelSnapshot texture = PanelSnapshot.builder().allCapabilities()
                .put(Settings.BACKGROUND_STYLE, "Animated texture").build();
        assertTrue(PanelPolicy.shouldRender(Settings.FORCE_DARK_BACKGROUND, texture));
        PanelSnapshot gradient = PanelSnapshot.builder().allCapabilities()
                .put(Settings.BACKGROUND_STYLE, "Gradient").build();
        assertFalse(PanelPolicy.shouldRender(Settings.FORCE_DARK_BACKGROUND, gradient));
        PanelSnapshot oldDevice = PanelSnapshot.builder()
                .put(Settings.BACKGROUND_STYLE, "Animated texture").build();
        assertFalse(PanelPolicy.shouldRender(Settings.FORCE_DARK_BACKGROUND, oldDevice));
    }

    @Test
    public void extraDarkNestsUnderForceDark() {
        PanelSnapshot on = PanelSnapshot.builder().allCapabilities()
                .put(Settings.BACKGROUND_STYLE, "Animated texture")
                .put(Settings.FORCE_DARK_BACKGROUND, true).build();
        assertTrue(PanelPolicy.shouldRender(Settings.EXTRA_DARK_BACKGROUND, on));
        PanelSnapshot off = PanelSnapshot.builder().allCapabilities()
                .put(Settings.BACKGROUND_STYLE, "Animated texture")
                .put(Settings.FORCE_DARK_BACKGROUND, false).build();
        assertFalse(PanelPolicy.shouldRender(Settings.EXTRA_DARK_BACKGROUND, off));
    }

    @Test
    public void fillAndGlowFollowAnimationStyle() {
        PanelSnapshot wash = PanelSnapshot.builder().allCapabilities()
                .put(Settings.ANIMATION_STYLE, "Gradient wash").build();
        assertTrue(PanelPolicy.shouldRender(Settings.LINE_SYNC_FILL, wash));
        PanelSnapshot spotlight = PanelSnapshot.builder().allCapabilities()
                .put(Settings.ANIMATION_STYLE, "Spotlight").build();
        assertFalse(PanelPolicy.shouldRender(Settings.LINE_SYNC_FILL, spotlight));
        PanelSnapshot karaoke = PanelSnapshot.builder().allCapabilities()
                .put(Settings.LIVE_CARD_ANIMATION, "Karaoke fill").build();
        assertTrue(PanelPolicy.shouldRender(Settings.LIVE_CARD_LINE_SYNC_FILL, karaoke));
        assertTrue(PanelPolicy.shouldRender(Settings.LIVE_CARD_GLOW, karaoke));
        PanelSnapshot minimal = PanelSnapshot.builder().allCapabilities()
                .put(Settings.LIVE_CARD_ANIMATION, "Minimal").build();
        assertFalse(PanelPolicy.shouldRender(Settings.LIVE_CARD_GLOW, minimal));
    }

    @Test
    public void customSizeRowsFollowTheirMode() {
        PanelSnapshot custom = PanelSnapshot.builder().allCapabilities()
                .put(Settings.LYRICS_TEXT_SIZE, "custom").build();
        assertTrue(PanelPolicy.shouldRender(Settings.LYRICS_TEXT_SIZE_CUSTOM, custom));
        assertFalse(PanelPolicy.shouldRender(Settings.LYRICS_TEXT_SIZE_CUSTOM, full()));
        PanelSnapshot trackCustom = PanelSnapshot.builder().allCapabilities()
                .put(Settings.TRACK_INFO_TEXT_SIZE, "Custom").build();
        assertTrue(PanelPolicy.shouldRender(Settings.TRACK_INFO_TEXT_SIZE_CUSTOM, trackCustom));
        assertFalse(PanelPolicy.shouldRender(Settings.TRACK_INFO_TEXT_SIZE_CUSTOM, full()));
    }

    @Test
    public void spicyTokenRowNeedsSpicyEnabled() {
        assertFalse(PanelPolicy.shouldRender(Settings.SPICY_MANUAL_TOKEN, full()));
        PanelSnapshot spicy = PanelSnapshot.builder().allCapabilities()
                .spicySourceEnabled(true).build();
        assertTrue(PanelPolicy.shouldRender(Settings.SPICY_MANUAL_TOKEN, spicy));
    }

    @Test
    public void ordinaryRowsAlwaysRender() {
        assertTrue(PanelPolicy.shouldRender(Settings.TAP_SEEK_MODE, full()));
        assertTrue(PanelPolicy.shouldRender(Settings.CACHE_SIZE, full()));
        assertTrue(PanelPolicy.shouldRender(Settings.UI_LANGUAGE, PanelSnapshot.builder().build()));
    }

    // --- Availability ---

    @Test
    public void unavailableTracksBuildAndDeviceGaps() {
        assertTrue(PanelPolicy.unavailable(Settings.TRANSLATION_ENABLED, PanelSnapshot.builder().build()));
        assertFalse(PanelPolicy.unavailable(Settings.TRANSLATION_ENABLED, full()));
        assertTrue(PanelPolicy.unavailable(Settings.TRANSLITERATION_ENABLED,
                PanelSnapshot.builder().allCapabilities().transliterationAvailable(false).build()));
    }

    @Test
    public void liveCardSecondaryModeExplainsItsRequirements() {
        PanelSnapshot lite = PanelSnapshot.builder()
                .put(Settings.TRANSLITERATION_ENABLED, true)
                .put(Settings.TRANSLATION_ENABLED, true).build();
        assertEquals("Full build required", PanelPolicy.optionUnavailableReason(
                (Settings.StringSetting) Settings.LIVE_CARD_SECONDARY_MODE,
                "Transliteration", lite, strings()));
        PanelSnapshot fullOff = full();
        assertEquals("Enable transliteration", PanelPolicy.optionUnavailableReason(
                (Settings.StringSetting) Settings.LIVE_CARD_SECONDARY_MODE,
                "Both", fullOff, strings()));
        PanelSnapshot fullOn = PanelSnapshot.builder().allCapabilities()
                .put(Settings.TRANSLITERATION_ENABLED, true)
                .put(Settings.TRANSLATION_ENABLED, true).build();
        assertEquals("", PanelPolicy.optionUnavailableReason(
                (Settings.StringSetting) Settings.LIVE_CARD_SECONDARY_MODE,
                "Both", fullOn, strings()));
    }

    @Test
    public void animatedBackgroundOptionNamesItsDeviceRequirement() {
        PanelSnapshot oldDevice = PanelSnapshot.builder().build();
        assertEquals("Android 13+ required", PanelPolicy.optionUnavailableReason(
                (Settings.StringSetting) Settings.BACKGROUND_STYLE,
                "Animated texture", oldDevice, strings()));
        assertEquals("", PanelPolicy.optionUnavailableReason(
                (Settings.StringSetting) Settings.BACKGROUND_STYLE,
                "Gradient", oldDevice, strings()));
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
        // UI language takes the full rebuild path (every label changes), not the section path.
        assertFalse(PanelPolicy.shouldRebuildSectionAfterChange(Settings.UI_LANGUAGE));
        assertFalse(PanelPolicy.shouldRebuildSectionAfterChange(Settings.TAP_SEEK_MODE));
        assertFalse(PanelPolicy.shouldRebuildSectionAfterChange(Settings.CACHE_SIZE));
    }

    @Test
    public void snapshotReadsDefaultsForMissingKeys() {
        PanelSnapshot empty = PanelSnapshot.builder().build();
        assertEquals(Settings.TAP_SEEK_MODE.defaultValue, empty.get(Settings.TAP_SEEK_MODE));
        assertEquals(Settings.SYNC_OFFSET_MS.defaultValue, empty.get(Settings.SYNC_OFFSET_MS));
        assertFalse(empty.isAiEnabled());
    }

    /**
     * A stray tap in an open language list must not change the interface language. Every
     * language-class picker commits only on an explicit Save; UI language is the one whose
     * accidental commitment re-renders the whole panel and reads as "the UI changed language
     * on its own".
     */
    @Test
    public void languageClassSelectorsCommitOnlyOnSave() {
        Settings.Setting<?>[] languageSelectors = {
                Settings.UI_LANGUAGE,
                Settings.TRANSLATION_TARGET,
                Settings.JAPANESE_READING_MODE,
                Settings.CHINESE_MODE,
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

    @Test
    public void appleSectionRendersOnlyUnderAppleMusicStyle() {
        Settings.Setting<?>[] appleRows = {
                Settings.APPLE_FADE_PASSED_LINES,
                Settings.APPLE_COMPACT_TEXT,
                Settings.APPLE_CJK_WRAP_FIX,
                Settings.LINE_SLIDE_ANIMATION,
                Settings.APPLE_LIFT
        };
        PanelSnapshot wash = PanelSnapshot.builder().allCapabilities()
                .put(Settings.ANIMATION_STYLE, "Gradient wash").build();
        PanelSnapshot spotlight = PanelSnapshot.builder().allCapabilities()
                .put(Settings.ANIMATION_STYLE, "Spotlight").build();
        PanelSnapshot apple = PanelSnapshot.builder().allCapabilities()
                .put(Settings.ANIMATION_STYLE, "Apple Music").build();
        for (Settings.Setting<?> setting : appleRows) {
            assertTrue(PanelPolicy.isAppleOwned(setting));
            assertFalse(setting.key + " must hide outside Apple Music",
                    PanelPolicy.shouldRender(setting, wash));
            assertFalse(setting.key + " must hide under Spotlight",
                    PanelPolicy.shouldRender(setting, spotlight));
            assertTrue(setting.key + " must show under Apple Music",
                    PanelPolicy.shouldRender(setting, apple));
        }
        assertFalse(PanelPolicy.isAppleOwned(Settings.WORD_BOUNCE_STYLE));
        assertFalse(PanelPolicy.isAppleOwned(Settings.ENABLE_LINE_BLUR));
    }

    @Test
    public void bounceRowsStandDownUnderAppleMusic() {
        PanelSnapshot wash = PanelSnapshot.builder().allCapabilities()
                .put(Settings.ANIMATION_STYLE, "Gradient wash").build();
        PanelSnapshot apple = PanelSnapshot.builder().allCapabilities()
                .put(Settings.ANIMATION_STYLE, "Apple Music").build();
        assertTrue(PanelPolicy.shouldRender(Settings.WORD_BOUNCE, wash));
        assertTrue(PanelPolicy.shouldRender(Settings.WORD_BOUNCE_STYLE, wash));
        assertFalse(PanelPolicy.shouldRender(Settings.WORD_BOUNCE, apple));
        assertFalse(PanelPolicy.shouldRender(Settings.WORD_BOUNCE_STYLE, apple));
    }
}
