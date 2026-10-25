package com.eza.spicyex.settings.panel;

import com.eza.spicyex.Settings;
import com.eza.spicyex.settings.registry.UiContext;

/**
 * Pure panel policy: availability, commit policy, and rebuild scope.
 *
 * <p>Row visibility is not here: it lives in the control registry, so the panel and the editor
 * read their rows from one place. Reads only an immutable {@link UiContext}; capability checks
 * arrive as snapshot flags, never as statics.
 */
public final class PanelPolicy {
    private PanelPolicy() {
    }

    /** Current pack gating is visibility-only, so no row is dimmed. */
    public static boolean unavailable(Settings.Setting<?> setting) {
        return false;
    }

    /** Why one option is dimmed; empty means selectable. Locale-resolved, never hardcoded. */
    public static String optionUnavailableReason(Settings.StringSetting setting, String value,
                                                 UiContext context, PanelStrings strings) {
        if (setting != Settings.LIVE_CARD_SECONDARY_MODE) return "";
        boolean needsTransliteration = "Transliteration".equals(value) || "Both".equals(value);
        boolean needsTranslation = "Translation".equals(value) || "Both".equals(value);
        if (needsTransliteration
                && !Boolean.TRUE.equals(context.settings.get(Settings.TRANSLITERATION_ENABLED))) {
            return strings.get("settings_enable_transliteration", "Enable transliteration");
        }
        if (needsTranslation
                && !Boolean.TRUE.equals(context.settings.get(Settings.TRANSLATION_ENABLED))) {
            return strings.get("settings_enable_translation", "Enable translation");
        }
        return "";
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
                || setting == Settings.CHINESE_CHARACTER_FORM
                || setting == Settings.KOREAN_ROMANIZATION
                || setting == Settings.CYRILLIC_MODE;
    }

    /** UI language rebuilds every label; dependency settings rebuild only their own section. */
    public static boolean shouldRebuildSectionAfterChange(Settings.Setting<?> setting) {
        return setting == Settings.AUTO_ENABLED
                || setting == Settings.PIP_ENABLED
                || setting == Settings.AI_ENABLED
                || setting == Settings.AI_PROVIDER
                || setting == Settings.TRANSLATION_ENABLED
                || setting == Settings.TRANSLITERATION_ENABLED
                || setting == Settings.BACKGROUND_STYLE
                || setting == Settings.ANIMATION_STYLE
                || setting == Settings.LIVE_CARD_ANIMATION
                || setting == Settings.LYRICS_TEXT_SIZE
                || setting == Settings.LINE_SPACING
                || setting == Settings.LIVE_CARD_TEXT_SIZE
                || setting == Settings.TRACK_INFO_TEXT_SIZE
                || setting == Settings.LYRICS_SOURCE_OVERRIDE
                || setting == Settings.LYRICS_SOURCE_MODE
                || setting == Settings.JAPANESE_READING_MODE;
    }
}
