package com.eza.spicyex;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Owns the settings schema: keys, types, defaults, sections, and allowed values.
 * Does not own persistence, normalized runtime reads, or UI row construction.
 * Only non-INTERNAL settings are shown in the in-Spotify panel; INTERNAL ones are fixed defaults.
 */
public final class Settings {
    public static final List<Setting<?>> ALL = new ArrayList<>();

    // --- Sections ---
    public static final Section LYRICS = new Section("General", "lyrics");
    public static final Section TRANSLITERATION = new Section("Reading & Transliteration", "transliteration");
    public static final Section ROMANIZATION = TRANSLITERATION;
    public static final Section TRANSLATION = new Section("Translation", "translation");
    public static final Section NOW_PLAYING = new Section("Now Playing Card", "now_playing");
    public static final Section LYRICS_SCREEN = new Section("Lyrics Screen", "lyrics_screen");
    public static final Section TEXT = LYRICS_SCREEN;
    public static final Section ANIMATION = LYRICS_SCREEN;
    public static final Section BACKGROUND = LYRICS_SCREEN;
    public static final Section DEBUG = new Section("Debug & About", "debug");
    public static final Section DISPLAY = TEXT;
    public static final Section INTERNAL = new Section("Internal", "internal");

    // ===================== USER-FACING =====================

    // NOTE: panel section order = order sections first appear here (SettingsPanel.renderSections
    // groups by declaration order in ALL). Keep each section's settings contiguous.

    // --- Lyrics ---
    public static final Setting<String> TAP_SEEK_MODE = enumSetting(
            "lyric_tap_seek_mode", LYRICS, "Tap lyric to seek",
            "Double tap",
            "Off", "Single tap", "Double tap"
    );

    // When on, the lyric screen stays open across track changes (Spotify's implicit finish() on
    // song change is suppressed) and reloads for the new track; explicit back/header-close still exits.
    public static final Setting<Boolean> STAY_IN_LYRICS = boolSetting(
            "lyric_stay_on_track_change", LYRICS, "Stay in lyric screen on song change", true
    );

    public static final IntegerSetting SYNC_OFFSET_MS = intSetting(
            "lyric_sync_offset_ms", LYRICS, "Sync offset",
            0, -5000, 5000, 100
    );

    // --- Now Playing ---
    public static final Setting<String> LIVE_CARD_TAP_MODE = enumSetting(
            "lyrics_live_card_tap_mode", NOW_PLAYING, "Tap now-playing lyric",
            "Double tap",
            "Off", "Single tap", "Double tap"
    );

    public static final Setting<String> LIVE_CARD_WEIGHT = enumSetting(
            "lyrics_live_card_weight", NOW_PLAYING, "Now-playing lyric weight",
            "Medium",
            "Regular", "Medium", "Bold"
    );

    public static final Setting<String> LIVE_CARD_TEXT_SIZE = enumSetting(
            "lyrics_live_card_text_size", NOW_PLAYING, "Now-playing lyric size",
            "normal",
            "small", "normal", "large", "xlarge"
    );

    public static final Setting<String> LIVE_CARD_SECONDARY_MODE = enumSetting(
            "lyrics_live_card_secondary_mode", NOW_PLAYING, "Now-playing extra line",
            "Main only",
            "Main only", "Transliteration", "Translation", "Both"
    );

    public static final Setting<String> LIVE_CARD_ANIMATION = enumSetting(
            "lyrics_live_card_animation", NOW_PLAYING, "Now-playing animation",
            "Karaoke fill",
            "Minimal", "Karaoke fill", "Spotlight word"
    );

    public static final Setting<String> LIVE_CARD_GLOW = enumSetting(
            "lyrics_live_card_glow", NOW_PLAYING, "Now-playing glow",
            "Off",
            "Off", "Word only", "Subtle line"
    );

    public static final Setting<String> LIVE_CARD_LINE_SYNC_FILL = enumSetting(
            "lyrics_live_card_line_sync_fill", NOW_PLAYING, "Now-playing fill direction",
            "Top to bottom",
            "Top to bottom", "Left to right (sentence)"
    );

    public static final Setting<String> LIVE_CARD_OVERFLOW = enumSetting(
            "lyrics_live_card_overflow", NOW_PLAYING, "Now-playing overflow",
            "Wrap",
            "Wrap", "Scroll with lyric", "Clip"
    );

    public static final Setting<String> LIVE_CARD_SCROLL_SCOPE = enumSetting(
            "lyrics_live_card_scroll_scope", NOW_PLAYING, "Now-playing scroll scope",
            "Grouped",
            "Grouped", "Individual lines"
    );

    public static final Setting<String> LIVE_CARD_TRANSITION = enumSetting(
            "lyrics_live_card_transition", NOW_PLAYING, "Now-playing transition",
            "Fade up",
            "Fade up", "Crossfade", "None"
    );

    // --- Text ---
    public static final Setting<String> LINE_SPACING = enumSetting(
            "line_spacing", TEXT, "Line spacing",
            "spacious",
            "compact", "default", "spacious", "more", "max"
    );

    // Lyric font weight (Spotify's own faces): "Medium" (default) = spotify_mix_ui_bold,
    // "Bold" = the heavy title-extrabold (was the old default — too thick for some), "Regular".
    public static final Setting<String> LYRICS_WEIGHT = enumSetting(
            "lyrics_weight", TEXT, "Lyric weight",
            "Medium",
            "Regular", "Medium", "Bold"
    );

    public static final Setting<String> LYRICS_FONT = enumSetting(
            "lyrics_font", TEXT, "Lyric font",
            "default",
            "default", "spotify", "apple"
    );

    public static final Setting<String> LYRICS_TEXT_SIZE = enumSetting(
            "lyrics_text_size", TEXT, "Lyric text size",
            "normal",
            "small", "normal", "large", "xlarge"
    );

    public static final Setting<String> INTERLUDE_ICON = enumSetting(
            "lyric_interlude_icon", TEXT, "Interlude indicator", "note",
            "dots", "note"
    );

    // --- Animation ---
    // "Gradient wash" = the karaoke fill sweeps each line (classic Spicy look).
    // "Spotlight" = no fill; the active line/word zooms + glows instead (gradient direction ignored).
    public static final Setting<String> ANIMATION_STYLE = enumSetting(
            "lyric_animation_style", ANIMATION, "Animation style",
            "Gradient wash",
            "Gradient wash", "Spotlight"
    );

    public static final Setting<Boolean> ENABLE_GLOW_BLUR = boolSetting(
            "lyric_enable_glow_blur", ANIMATION, "Glow blur", false
    );

    public static final Setting<Boolean> ENABLE_LINE_BLUR = boolSetting(
            "lyric_enable_line_blur", ANIMATION, "Distance blur", false
    );

    // Direction the karaoke gradient fills each line as it plays: down the line ("Top to bottom")
    // or word-by-word ("Left to right"). Applies under "Gradient wash" only.
    public static final Setting<String> LINE_SYNC_FILL = enumSetting(
            "lyric_line_sync_fill", ANIMATION, "Lyric fill direction",
            "Top to bottom",
            "Top to bottom", "Left to right (block)", "Left to right (sentence)"
    );

    // --- Background ---
    public static final Setting<Boolean> ENABLE_BACKGROUND = boolSetting(
            "lyric_enable_background", BACKGROUND, "Animated background", false
    );

    public static final Setting<Boolean> FORCE_DARK_BACKGROUND = boolSetting(
            "lyric_force_dark_background", BACKGROUND, "Force dark background", true
    );

    // --- Romanization (transliteration controls) ---
    public static final Setting<Boolean> TRANSLITERATION_ENABLED = boolSetting(
            "lyrics_transliteration_enabled", TRANSLITERATION, "Transliterate lyrics", false
    );

    // Global romanization layout — aligned under each word (great for language learners comparing
    // word-by-word) vs a single line. Applies to every romanizable script, not just one language.
    public static final Setting<Boolean> ALIGNED_PER_WORD_ROMAJI = boolSetting(
            "lyric_aligned_per_word_romaji", TRANSLITERATION, "Attach transliteration under each word", true
    );

    // "cycle" = the in-screen chip cycles the modes on tap; a fixed value locks to that mode.
    public static final Setting<String> JAPANESE_READING_MODE = enumSetting(
            "lyrics_japanese_reading_mode", TRANSLITERATION, "Japanese reading",
            "romaji_only",
            "off", "furigana_only", "furigana_romaji", "romaji_only", "cycle"
    );

    public static final Setting<String> CHINESE_MODE = enumSetting(
            "lyrics_chinese_mode", TRANSLITERATION, "Chinese transliteration",
            "pinyin",
            "off", "pinyin", "jyutping", "cycle"
    );

    // "Letter-by-letter" = literal per-syllable table (aromanize-compatible, default).
    // "Pronunciation" = jamo-aware pronunciation pass (liaison/nasalization/etc., see SpicyKoreanG2P).
    public static final Setting<String> KOREAN_ROMANIZATION = enumSetting(
            "lyrics_korean_romanization", TRANSLITERATION, "Korean romanization",
            "Pronunciation",
            "Off", "Letter-by-letter", "Pronunciation", "cycle"
    );

    // On = Mandarin pinyin tone marks (zhōng guó) + Cantonese jyutping tone numbers (nei5).
    // Off (default) = no tone marks / no jyutping tone numbers — cleaner lyric display.
    public static final Setting<Boolean> CHINESE_TONES = boolSetting(
            "lyrics_chinese_tones", TRANSLITERATION, "Show Chinese tones", false
    );

    // Cyrillic source language — shared glyphs differ (Russian г→g, и→i vs Ukrainian г→h, и→y),
    // so one global map can't serve both (see SpicyRomanizer / ROMANIZATION_AUDIT_BACKLOG CY-4).
    public static final Setting<String> CYRILLIC_MODE = enumSetting(
            "lyrics_cyrillic_mode", TRANSLITERATION, "Cyrillic language",
            "Russian",
            "Off", "Russian", "Ukrainian", "cycle"
    );

    // Off (default) = drop ь/ъ for readability; On = keep them as BGN/PCGN prime marks (ʹ/ʺ).
    public static final Setting<Boolean> CYRILLIC_KEEP_SIGNS = boolSetting(
            "lyrics_cyrillic_keep_signs", TRANSLITERATION, "Keep Cyrillic soft/hard signs", false
    );

    // --- Translation ---
    public static final Setting<Boolean> TRANSLATION_ENABLED = boolSetting(
            "lyrics_translation_enabled", TRANSLATION, "Translate lyrics", false
    );

    public static final Setting<String> TRANSLATION_TARGET = enumSetting(
            "lyrics_translation_target", TRANSLATION, "Target language",
            "en",
            "en", "es", "fr", "de", "it", "pt", "nl", "sv", "no", "da",
            "fi", "pl", "cs", "sk", "hu", "ro", "el", "tr", "uk", "ru",
            "ja", "ko", "zh", "zh-TW", "th", "vi", "id", "ms", "hi", "bn",
            "ta", "ar", "he", "fa"
    );

    public static final Setting<String> TRANSLATION_BRIGHTNESS = enumSetting(
            "lyrics_translation_brightness", TRANSLATION, "Translation line",
            "Dimmed",
            "Dimmed", "Bright"
    );

    // ===================== INTERNAL (fixed defaults, not shown) =====================

    public static final Setting<String> DISPLAY_MODE = internalEnumSetting(
            "lyrics_display_mode", "Display mode",
            "original_romanized",
            "original", "romanized", "original_romanized",
            "original_translation", "original_romanized_translation"
    );

    // The native lyrics feature is always on — disabling it removes every entry path (button + this
    // very settings panel), so it's not a user choice.
    public static final Setting<Boolean> NATIVE_SPICY_ENABLED = internalBoolSetting(
            "native_spicy_enabled", "Enable native Spicy lyrics screen", true
    );

    public static final Setting<Boolean> SEND_TOKEN = internalBoolSetting(
            "lyrics_send_token", "Send token", true
    );

    public static final Setting<Boolean> NATIVE_SPICY_ROMANIZATION = internalBoolSetting(
            "native_spicy_romanization", "Enable Spicy romanization", false
    );

    public static final Setting<Boolean> NATIVE_SPICY_TRANSLATION = internalBoolSetting(
            "native_spicy_translation", "Enable Spicy translation", false
    );

    public static final Setting<Boolean> LIVE_CARD_SHOW_TRANSLITERATION = internalBoolSetting(
            "lyrics_live_card_show_transliteration", "Now-playing transliteration", false
    );

    public static final Setting<String> LAST_JAPANESE_CYCLE_MODE = internalEnumSetting(
            "lyrics_last_japanese_cycle_mode", "Last Japanese cycle mode",
            SpotifyPlusConfig.JP_READING_ROMAJI_ONLY,
            SpotifyPlusConfig.JP_READING_FURIGANA_ONLY,
            SpotifyPlusConfig.JP_READING_ROMAJI_ONLY,
            SpotifyPlusConfig.JP_READING_FURIGANA_ROMAJI
    );

    public static final Setting<String> LAST_CHINESE_CYCLE_MODE = internalEnumSetting(
            "lyrics_last_chinese_cycle_mode", "Last Chinese cycle mode",
            SpotifyPlusConfig.CHINESE_MODE_PINYIN,
            SpotifyPlusConfig.CHINESE_MODE_PINYIN,
            SpotifyPlusConfig.CHINESE_MODE_JYUTPING
    );

    public static final Setting<String> LAST_KOREAN_CYCLE_MODE = internalEnumSetting(
            "lyrics_last_korean_cycle_mode", "Last Korean cycle mode",
            "Pronunciation",
            "Letter-by-letter", "Pronunciation"
    );

    public static final Setting<String> LAST_CYRILLIC_CYCLE_MODE = internalEnumSetting(
            "lyrics_last_cyrillic_cycle_mode", "Last Cyrillic cycle mode",
            "Russian",
            "Russian", "Ukrainian"
    );

    public static final Setting<String> SOURCE_LANGUAGE_MODE = internalEnumSetting(
            "lyrics_source_language_mode", "Source language mode",
            "auto",
            "auto", "manual"
    );

    public static final Setting<String> SOURCE_LANGUAGE = internalEnumSetting(
            "lyrics_source_language", "Manual source language",
            "auto",
            "auto", "ja", "zh", "ko", "ru", "uk", "bg", "sr", "mk", "be",
            "el", "ar", "fa", "ur", "he", "th", "hi", "bn", "ta", "te",
            "ka", "hy", "am", "my", "km", "lo", "other"
    );

    public static final Setting<String> TRANSLATION_BACKEND = internalEnumSetting(
            "lyrics_translation_backend", "Translation backend",
            "google_unofficial",
            "provider", "google_unofficial", "disabled"
    );

    public static final Setting<String> BACKGROUND_QUALITY = internalEnumSetting(
            "lyric_background_quality", "Render quality (background & blur)",
            "high",
            "high", "mid", "low", "superLow"
    );

    public static final Setting<Boolean> ENABLE_LINE_GRADIENT = internalBoolSetting(
            "lyric_enable_line_gradient", "Line gradient/glow", true
    );

    public static final Setting<Boolean> TOGGLE_PROGRESS_RING = internalBoolSetting(
            "lyric_toggle_progress_ring", "Progress ring on toggle buttons", true
    );

    public static final Setting<Boolean> SHOW_SKELETON = internalBoolSetting(
            "lyric_show_skeleton", "Skeleton placeholder while loading", true
    );

    public static final Setting<String> LAST_CACHE_CLEAR_VERSION = internalSetting(
            "last_cache_clear_version", "last_cache_clear_version", ""
    );

    // --- Helper classes ---

    public static final class Section {
        public final String label;
        public final String id;

        Section(String label, String id) {
            this.label = label;
            this.id = id;
        }
    }

    public static abstract class Setting<T> {
        public final String key;
        public final Section section;
        public final String label;
        public final T defaultValue;
        public final List<T> allowedValues;

        Setting(String key, Section section, String label, T defaultValue, List<T> allowedValues) {
            this.key = key;
            this.section = section;
            this.label = label;
            this.defaultValue = defaultValue;
            this.allowedValues = allowedValues != null ? Collections.unmodifiableList(allowedValues) : null;
            ALL.add(this);
        }

        public abstract T coerce(Object value);
    }

    public static final class BooleanSetting extends Setting<Boolean> {
        BooleanSetting(String key, Section section, String label, Boolean defaultValue) {
            super(key, section, label, defaultValue, null);
        }

        @Override
        public Boolean coerce(Object value) {
            if (value instanceof Boolean) return (Boolean) value;
            if (value instanceof String) return Boolean.parseBoolean((String) value);
            return defaultValue;
        }
    }

    public static final class StringSetting extends Setting<String> {
        StringSetting(String key, Section section, String label, String defaultValue, List<String> allowedValues) {
            super(key, section, label, defaultValue, allowedValues);
        }

        @Override
        public String coerce(Object value) {
            if (!(value instanceof String)) return defaultValue;
            String s = (String) value;
            if (allowedValues != null && !allowedValues.contains(s)) return defaultValue;
            return s;
        }
    }

    public static final class IntegerSetting extends Setting<Integer> {
        public final int minValue;
        public final int maxValue;
        public final int stepValue;

        IntegerSetting(String key, Section section, String label, Integer defaultValue,
                       int minValue, int maxValue, int stepValue) {
            super(key, section, label, defaultValue, null);
            this.minValue = minValue;
            this.maxValue = maxValue;
            this.stepValue = Math.max(1, stepValue);
        }

        @Override
        public Integer coerce(Object value) {
            int result = defaultValue;
            if (value instanceof Integer) {
                result = (Integer) value;
            } else if (value instanceof Long) {
                result = (int) ((Long) value).longValue();
            } else if (value instanceof String) {
                try {
                    result = Integer.parseInt((String) value);
                } catch (NumberFormatException ignored) {
                    result = defaultValue;
                }
            }
            return Math.max(minValue, Math.min(maxValue, result));
        }
    }

    private static Setting<Boolean> boolSetting(String key, Section section, String label, boolean defaultValue) {
        return new BooleanSetting(key, section, label, defaultValue);
    }

    private static IntegerSetting intSetting(String key, Section section, String label,
                                             int defaultValue, int minValue, int maxValue, int stepValue) {
        return new IntegerSetting(key, section, label, defaultValue, minValue, maxValue, stepValue);
    }

    private static Setting<Boolean> internalBoolSetting(String key, String label, boolean defaultValue) {
        return new BooleanSetting(key, INTERNAL, label, defaultValue);
    }

    private static Setting<String> enumSetting(String key, Section section, String label, String defaultValue, String... allowed) {
        return new StringSetting(key, section, label, defaultValue, Arrays.asList(allowed));
    }

    private static Setting<String> internalEnumSetting(String key, String label, String defaultValue, String... allowed) {
        return new StringSetting(key, INTERNAL, label, defaultValue, Arrays.asList(allowed));
    }

    private static Setting<String> internalSetting(String key, String label, String defaultValue) {
        return new StringSetting(key, INTERNAL, label, defaultValue, null);
    }
}
