package com.eza.spicyex.settings;

import com.eza.spicyex.Settings;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Explicit UI registry over the {@link Settings} persistence schema.
 *
 * <p>Panel section order and row order used to be implicit contracts of declaration order in
 * {@code Settings.ALL} — a different file's line order decided what the panel showed and in
 * which order. Both are stated here as data instead, and {@code SettingsUiSchemaOrderTest}
 * fails if this list stops matching the renderable setting set exactly.
 *
 * <p>Composite settings map to hand-built rows; everything else maps to one renderer per kind.
 * DEBUG renders separately; INTERNAL never renders and is deliberately absent below.
 */
public final class SettingsUiSchema {
    private SettingsUiSchema() {
    }

    /** Panel section order, first to last. DEBUG is rendered separately; INTERNAL never renders. */
    public static List<Settings.Section> orderedSections() {
        return Collections.unmodifiableList(Arrays.asList(
                Settings.GENERAL,
                Settings.LYRICS,
                Settings.GESTURES,
                Settings.LYRICS_SOURCES,
                Settings.NOW_PLAYING,
                Settings.LYRICS_SCREEN,
                Settings.APPLE,
                Settings.TRANSLITERATION,
                Settings.TRANSLATION,
                Settings.AI,
                Settings.ADS));
    }

    /**
     * Every renderable setting in panel row order. Grouped by section in the same order as
     * {@link #orderedSections()}, so the panel can filter this list per section and keep both
     * the grouping and the intra-section order explicit.
     */
    private static final List<Settings.Setting<?>> ORDERED = Collections.unmodifiableList(Arrays.asList(
            // Behavior
            Settings.UI_LANGUAGE,
            Settings.TAP_SEEK_MODE,
            Settings.STAY_IN_LYRICS,
            Settings.AUTO_RESUME_FOLLOW,
            Settings.AUTO_RESUME_FOLLOW_DELAY_SECONDS,
            Settings.AUTO_SKIP_INTRO_OUTRO,
            Settings.MINI_PLAYER_LYRICS_ICON,
            Settings.STATUS_BAR_HIDDEN_PORTRAIT,
            Settings.STATUS_BAR_HIDDEN_LANDSCAPE,
            Settings.LONG_PRESS_SHARE,
            Settings.AD_MODE,
            Settings.AD_MUSIC_THEME,
            Settings.SYNC_OFFSET_MS,
            Settings.HYPERGLOW_ENABLED,
            // Lyrics sources
            Settings.LYRICS_SOURCE_MODE,
            Settings.LYRICS_SOURCE_OVERRIDE,
            Settings.SPICY_MANUAL_TOKEN,
            Settings.LYRICS_SOURCE_ORDER,
            Settings.KARAOKE_ORIGINAL_LYRICS,
            Settings.CACHE_SIZE,
            // Now playing card
            Settings.LIVE_CARD_TAP_MODE,
            Settings.LIVE_CARD_TAP_TARGET,
            // Lyrics screen
            Settings.FULLSCREEN_CONTROLS,
            Settings.ANIMATION_STYLE,
            // Apple Music (dedicated section; renders only while the Animation style is Apple Music)
            Settings.APPLE_COMPACT_TEXT,
            // Reading & transliteration
            Settings.DOWNLOAD_LANGUAGE_MODELS,
            Settings.TRANSLITERATION_ENABLED,
            Settings.ALIGNED_PER_WORD_ROMAJI,
            Settings.JAPANESE_READING_MODE,
            Settings.FURIGANA_BRIGHTNESS,
            Settings.FURIGANA_POSITION_PERCENT,
            Settings.CHINESE_MODE,
            Settings.KOREAN_ROMANIZATION,
            Settings.CHINESE_TONES,
            Settings.CYRILLIC_MODE,
            Settings.CYRILLIC_KEEP_SIGNS,
            // Translation
            Settings.TRANSLATION_ENABLED,
            Settings.TRANSLATION_TARGET,
            Settings.TRANSLATION_BRIGHTNESS,
            // AI
            Settings.AI_ENABLED,
            Settings.AI_PROVIDER,
            Settings.AI_DEEPSEEK_REASONING,
            Settings.AI_TRANSLATION_MODE,
            Settings.AI_TRANSLATION_PIPELINE,
            Settings.AI_PRONUNCIATION_MODE,
            Settings.AI_PRONUNCIATION_SOURCE,
            Settings.AI_BUTTON_BEHAVIOR));

    /** Every renderable setting, in panel row order. */
    public static List<Settings.Setting<?>> orderedSettings() {
        return ORDERED;
    }

    /** Renderable settings belonging to one section, in panel row order. */
    public static List<Settings.Setting<?>> orderedSettings(Settings.Section section) {
        List<Settings.Setting<?>> items = new ArrayList<>();
        for (Settings.Setting<?> setting : ORDERED) {
            if (setting.section == section) items.add(setting);
        }
        return items;
    }

    public static SettingUiSpec specOf(Settings.Setting<?> setting) {
        return SettingUiSpec.of(setting);
    }

    /** True for settings rendered by explicit composite rows rather than a kind renderer. */
    public static boolean isComposite(Settings.Setting<?> setting) {
        return specOf(setting).kind == SettingUiSpec.RowKind.COMPOSITE;
    }
}
