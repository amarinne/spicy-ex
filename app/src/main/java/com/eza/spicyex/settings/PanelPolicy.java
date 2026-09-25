package com.eza.spicyex.settings;

import com.eza.spicyex.Settings;
import com.eza.spicyex.lyrics.LyricsBackgroundStyle;
import com.eza.spicyex.lyrics.ai.AiSettings;

/**
 * Pure panel policy: visibility, availability, commit policy, and rebuild scope.
 *
 * <p>Moved verbatim out of {@code SettingsPanel} so JVM tests cover the gating matrix.
 * Reads only a {@link PanelSnapshot}; capability checks arrive as snapshot flags, never as
 * statics, so Lite/Full behavior is a test input rather than a build artifact.
 */
public final class PanelPolicy {
    private PanelPolicy() {
    }

    public static boolean shouldRender(Settings.Setting<?> setting, PanelSnapshot snapshot) {
        if (setting == Settings.SPICY_MANUAL_TOKEN) {
            return snapshot.spicySourceEnabled();
        }
        if (setting == Settings.AI_ENABLED) return snapshot.aiOffered();
        if (setting == Settings.AI_DEEPSEEK_REASONING) {
            return AiSettings.PROVIDER_DEEPSEEK.equals(snapshot.get(Settings.AI_PROVIDER))
                    && aiSettingVisible(setting, snapshot.isAiEnabled());
        }
        if (setting.section == Settings.AI) {
            return aiSettingVisible(setting, snapshot.isAiEnabled());
        }
        if (setting == Settings.TRANSLATION_TARGET || setting == Settings.TRANSLATION_BRIGHTNESS) {
            return snapshot.translationAvailable()
                    && Boolean.TRUE.equals(snapshot.get(Settings.TRANSLATION_ENABLED));
        }
        if (setting == Settings.ALIGNED_PER_WORD_ROMAJI
                || setting == Settings.JAPANESE_READING_MODE
                || setting == Settings.CHINESE_MODE
                || setting == Settings.KOREAN_ROMANIZATION
                || setting == Settings.CHINESE_TONES
                || setting == Settings.CYRILLIC_MODE
                || setting == Settings.CYRILLIC_KEEP_SIGNS) {
            return snapshot.transliterationAvailable()
                    && Boolean.TRUE.equals(snapshot.get(Settings.TRANSLITERATION_ENABLED));
        }
        if (setting == Settings.FORCE_DARK_BACKGROUND) {
            return snapshot.animatedBackgroundAvailable()
                    && LyricsBackgroundStyle.usesTexture(snapshot.get(Settings.BACKGROUND_STYLE));
        }
        if (setting == Settings.EXTRA_DARK_BACKGROUND) {
            return shouldRenderForceDark(snapshot)
                    && Boolean.TRUE.equals(snapshot.get(Settings.FORCE_DARK_BACKGROUND));
        }
        if (setting == Settings.BACKGROUND_RENDER_QUALITY) {
            return snapshot.animatedBackgroundAvailable()
                    && LyricsBackgroundStyle.isAnimated(snapshot.get(Settings.BACKGROUND_STYLE));
        }
        if (setting == Settings.AD_MUSIC_THEME) {
            return Settings.AD_MODE_MUSIC.equals(snapshot.get(Settings.AD_MODE));
        }
        if (setting == Settings.LYRICS_BLUR_INTENSITY) {
            return !"Off".equals(snapshot.get(Settings.ENABLE_LINE_BLUR));
        }
        if (setting == Settings.LINE_SYNC_FILL) {
            return "Gradient wash".equals(snapshot.get(Settings.ANIMATION_STYLE));
        }
        if (setting == Settings.WORD_BOUNCE || setting == Settings.WORD_BOUNCE_STYLE) {
            // Apple motion is owned by the Apple section under Apple Music; the shared
            // bounce rows would compete, so they stand down while the Apple card is up.
            return !"Apple Music".equals(snapshot.get(Settings.ANIMATION_STYLE));
        }
        if (setting == Settings.APPLE_SPRING_STRENGTH) {
            return "Apple Music".equals(snapshot.get(Settings.ANIMATION_STYLE));
        }
        if (isAppleOwned(setting)) {
            return "Apple Music".equals(snapshot.get(Settings.ANIMATION_STYLE));
        }
        if (setting == Settings.LIVE_CARD_LINE_SYNC_FILL) {
            return "Karaoke fill".equals(snapshot.get(Settings.LIVE_CARD_ANIMATION));
        }
        if (setting == Settings.LIVE_CARD_GLOW) {
            return !"Minimal".equals(snapshot.get(Settings.LIVE_CARD_ANIMATION));
        }
        if (setting == Settings.LYRICS_TEXT_SIZE_CUSTOM) {
            return "custom".equals(snapshot.get(Settings.LYRICS_TEXT_SIZE));
        }
        if (setting == Settings.LINE_SPACING_CUSTOM) {
            return "custom".equals(snapshot.get(Settings.LINE_SPACING));
        }
        if (setting == Settings.LIVE_CARD_TEXT_SIZE_CUSTOM) {
            return "custom".equals(snapshot.get(Settings.LIVE_CARD_TEXT_SIZE));
        }
        if (setting == Settings.TRACK_INFO_TEXT_SIZE_CUSTOM) {
            return "Custom".equals(snapshot.get(Settings.TRACK_INFO_TEXT_SIZE));
        }
        if (setting == Settings.LYRICS_FONT_CUSTOM_PATH) {
            return "custom".equals(snapshot.get(Settings.LYRICS_FONT));
        }
        if (setting == Settings.AUTO_RESUME_FOLLOW_DELAY_SECONDS) {
            return Boolean.TRUE.equals(snapshot.get(Settings.AUTO_RESUME_FOLLOW));
        }
        if (setting == Settings.FURIGANA_BRIGHTNESS || setting == Settings.FURIGANA_POSITION_PERCENT) {
            String reading = snapshot.get(Settings.JAPANESE_READING_MODE);
            return snapshot.transliterationAvailable()
                    && Boolean.TRUE.equals(snapshot.get(Settings.TRANSLITERATION_ENABLED))
                    && ("furigana_only".equals(reading) || "furigana_romaji".equals(reading));
        }
        return true;
    }

    private static boolean shouldRenderForceDark(PanelSnapshot snapshot) {
        return snapshot.animatedBackgroundAvailable()
                && LyricsBackgroundStyle.usesTexture(snapshot.get(Settings.BACKGROUND_STYLE));
    }

    /** Keep AI master switch visible; nest every other AI setting under that switch. */
    public static boolean aiSettingVisible(Settings.Setting<?> setting, boolean enabled) {
        return setting == Settings.AI_ENABLED || enabled;
    }

    /**
     * Apple-owned dedicated section (R3): the Apple Music card appears only while the Animation
     * style is Apple Music. Each key is read only under that style, so switching styles never
     * rewrites shared keys and no prior-value snapshot/restore cycle is needed.
     */
    public static boolean isAppleOwned(Settings.Setting<?> setting) {
        return setting == Settings.APPLE_FADE_PASSED_LINES
                || setting == Settings.APPLE_COMPACT_TEXT
                || setting == Settings.LINE_SLIDE_ANIMATION
                || setting == Settings.APPLE_LIFT
                || setting == Settings.LOAD_LIFT_ANIMATION
                || setting == Settings.APPLE_CASCADE_SPEED;
    }

    public static boolean unavailable(Settings.Setting<?> setting, PanelSnapshot snapshot) {
        return (setting == Settings.TRANSLITERATION_ENABLED && (!snapshot.transliterationAvailable() || !snapshot.languageModelReady()))
                || (setting == Settings.TRANSLATION_ENABLED && !snapshot.translationAvailable())
                || (setting == Settings.LYRICS_FONT && !snapshot.appleFontAvailable());
    }

    /** Why one option is dimmed; empty means selectable. Locale-resolved, never hardcoded. */
    public static String optionUnavailableReason(Settings.StringSetting setting, String value,
                                                 PanelSnapshot snapshot, PanelStrings strings) {
        if (setting == Settings.BACKGROUND_STYLE
                && LyricsBackgroundStyle.usesTexture(value)
                && !snapshot.animatedBackgroundAvailable()) {
            return strings.get("settings_unavailable_android_13", "Android 13+ required");
        }
        if (setting != Settings.LIVE_CARD_SECONDARY_MODE) return "";
        boolean needsTransliteration = "Transliteration".equals(value) || "Both".equals(value);
        boolean needsTranslation = "Translation".equals(value) || "Both".equals(value);
        if (needsTransliteration && !snapshot.transliterationAvailable()) {
            return fullBuildRequired(strings);
        }
        if (needsTransliteration && !snapshot.languageModelReady()) {
            return strings.get("settings_label_download_language_models", "Download language models");
        }
        if (needsTranslation && !snapshot.translationAvailable()) {
            return fullBuildRequired(strings);
        }
        if (needsTransliteration && !Boolean.TRUE.equals(snapshot.get(Settings.TRANSLITERATION_ENABLED))) {
            return strings.get("settings_enable_transliteration", "Enable transliteration");
        }
        if (needsTranslation && !Boolean.TRUE.equals(snapshot.get(Settings.TRANSLATION_ENABLED))) {
            return strings.get("settings_enable_translation", "Enable translation");
        }
        return "";
    }

    /**
     * A non-blocking note for an option: picking it is allowed, the note says what it changes.
     * Tap-to-seek on double tap and double-tap to like share the gesture, so choosing one turns
     * the other off.
     */
    public static String optionNote(Settings.StringSetting setting, String value,
                                    PanelSnapshot snapshot, PanelStrings strings) {
        if (setting == Settings.TAP_SEEK_MODE && "Double tap".equals(value)
                && Boolean.TRUE.equals(snapshot.get(Settings.DOUBLE_TAP_LIKE))) {
            return strings.get("settings_tap_seek_double_tap_turns_off_like",
                    "Turns off double-tap to like");
        }
        return "";
    }

    private static String fullBuildRequired(PanelStrings strings) {
        return strings.get("settings_unavailable_full_build", "Full build required");
    }

    /**
     * Which commit policy a setting uses.
     *
     * <p>The six language-class pickers are {@code CONFIRMING}: they accumulate a pending
     * highlight and write only on an explicit Save, so a stray tap in an open list cannot change
     * the interface language. This mirrors AndroidX {@code ListPreference}, which persists only
     * in {@code onDialogClosed(positiveResult=true)}. Steppers settle after the last tick;
     * everything else writes at once.
     */
    public static CommitPolicy commitPolicyFor(Settings.Setting<?> setting) {
        if (isConfirmingSelector(setting)) return CommitPolicy.CONFIRMING;
        if (setting instanceof Settings.IntegerSetting) return CommitPolicy.DEBOUNCED;
        return CommitPolicy.IMMEDIATE;
    }

    /**
     * Selectors that must never commit from a single tap. UI language is the dangerous one: an
     * accidental pick re-renders every label, which reads as "the UI changed language on its own".
     */
    public static boolean isConfirmingSelector(Settings.Setting<?> setting) {
        return setting == Settings.UI_LANGUAGE
                || setting == Settings.TRANSLATION_TARGET
                || setting == Settings.JAPANESE_READING_MODE
                || setting == Settings.CHINESE_MODE
                || setting == Settings.KOREAN_ROMANIZATION
                || setting == Settings.CYRILLIC_MODE;
    }

    /** UI language rebuilds every label; dependency settings rebuild only their own section. */
    public static boolean shouldRebuildSectionAfterChange(Settings.Setting<?> setting) {
        return setting == Settings.AI_ENABLED
                || setting == Settings.DOUBLE_TAP_LIKE
                || setting == Settings.TAP_SEEK_MODE
                || setting == Settings.AI_PROVIDER
                || setting == Settings.TRANSLATION_ENABLED
                || setting == Settings.TRANSLITERATION_ENABLED
                || setting == Settings.BACKGROUND_STYLE
                || setting == Settings.FORCE_DARK_BACKGROUND
                || setting == Settings.ANIMATION_STYLE
                || setting == Settings.LIVE_CARD_ANIMATION
                || setting == Settings.LYRICS_TEXT_SIZE
                || setting == Settings.LYRICS_FONT
                || setting == Settings.LYRICS_FONT_CUSTOM_PATH
                || setting == Settings.LINE_SPACING
                || setting == Settings.LIVE_CARD_TEXT_SIZE
                || setting == Settings.TRACK_INFO_TEXT_SIZE
                || setting == Settings.LYRICS_SOURCE_OVERRIDE
                || setting == Settings.LYRICS_SOURCE_MODE;
    }
}
