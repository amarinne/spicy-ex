package com.eza.spicyex;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Single source of truth for all SpotifyPlus settings (keys, types, defaults, allowed values).
 * Only non-INTERNAL settings are shown in the in-Spotify panel; INTERNAL ones are fixed defaults.
 */
public final class Settings {
    public static final List<Setting<?>> ALL = new ArrayList<>();

    // --- Sections ---
    public static final Section LYRICS = new Section("Lyrics", "lyrics");
    public static final Section ROMANIZATION = new Section("Romanization", "romanization");
    public static final Section TRANSLATION = new Section("Translation", "translation");
    public static final Section DISPLAY = new Section("Display", "display");
    public static final Section INTERNAL = new Section("Internal", "internal");

    // ===================== USER-FACING =====================

    // NOTE: panel section order = order sections first appear here (SettingsPanel.renderSections
    // groups by declaration order in ALL). Keep each section's settings contiguous.

    // --- Translation --- (shown first)
    public static final Setting<Boolean> TRANSLATION_ENABLED = boolSetting(
            "lyrics_translation_enabled", TRANSLATION, "Translate lyrics", true
    );

    public static final Setting<String> TRANSLATION_TARGET = enumSetting(
            "lyrics_translation_target", TRANSLATION, "Target language",
            "en",
            "en", "es", "fr", "de", "it", "pt", "nl", "sv", "no", "da",
            "fi", "pl", "cs", "sk", "hu", "ro", "el", "tr", "uk", "ru",
            "ja", "ko", "zh", "zh-TW", "th", "vi", "id", "ms", "hi", "bn",
            "ta", "ar", "he", "fa"
    );

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

    // --- Romanization (transliteration controls) ---
    // Global romanization layout — aligned under each word (great for language learners comparing
    // word-by-word) vs a single line. Applies to every romanizable script, not just one language.
    public static final Setting<Boolean> ALIGNED_PER_WORD_ROMAJI = boolSetting(
            "lyric_aligned_per_word_romaji", ROMANIZATION, "Attach transliteration under each word", true
    );

    // "cycle" = the in-screen chip cycles the modes on tap; a fixed value locks to that mode.
    public static final Setting<String> JAPANESE_READING_MODE = enumSetting(
            "lyrics_japanese_reading_mode", ROMANIZATION, "Japanese reading",
            "cycle",
            "furigana_only", "furigana_romaji", "romaji_only", "cycle"
    );

    public static final Setting<String> CHINESE_MODE = enumSetting(
            "lyrics_chinese_mode", ROMANIZATION, "Chinese transliteration",
            "cycle",
            "pinyin", "jyutping", "cycle"
    );

    // "Letter-by-letter" = literal per-syllable table (aromanize-compatible, default).
    // "Pronunciation" = jamo-aware pronunciation pass (liaison/nasalization/etc., see SpicyKoreanG2P).
    public static final Setting<String> KOREAN_ROMANIZATION = enumSetting(
            "lyrics_korean_romanization", ROMANIZATION, "Korean romanization",
            "Letter-by-letter",
            "Letter-by-letter", "Pronunciation"
    );

    // On = Mandarin pinyin tone marks (zhōng guó) + Cantonese jyutping tone numbers (nei5).
    // Off (default) = no tone marks / no jyutping tone numbers — cleaner lyric display.
    public static final Setting<Boolean> CHINESE_TONES = boolSetting(
            "lyrics_chinese_tones", ROMANIZATION, "Show Chinese tones", false
    );

    // Cyrillic source language — shared glyphs differ (Russian г→g, и→i vs Ukrainian г→h, и→y),
    // so one global map can't serve both (see SpicyRomanizer / ROMANIZATION_AUDIT_BACKLOG CY-4).
    public static final Setting<String> CYRILLIC_MODE = enumSetting(
            "lyrics_cyrillic_mode", ROMANIZATION, "Cyrillic language",
            "Russian",
            "Russian", "Ukrainian"
    );

    // Off (default) = drop ь/ъ for readability; On = keep them as BGN/PCGN prime marks (ʹ/ʺ).
    public static final Setting<Boolean> CYRILLIC_KEEP_SIGNS = boolSetting(
            "lyrics_cyrillic_keep_signs", ROMANIZATION, "Keep Cyrillic soft/hard signs", false
    );

    // --- Display ---
    public static final Setting<Boolean> ENABLE_BACKGROUND = boolSetting(
            "lyric_enable_background", DISPLAY, "Animated background", false
    );

    public static final Setting<Boolean> FORCE_DARK_BACKGROUND = boolSetting(
            "lyric_force_dark_background", DISPLAY, "Force dark background", true
    );

    public static final Setting<String> LINE_SPACING = enumSetting(
            "line_spacing", DISPLAY, "Line spacing",
            "more",
            "compact", "default", "spacious", "more", "max"
    );

    // Lyric font weight (Spotify's own faces): "Medium" (default) = spotify_mix_ui_bold,
    // "Bold" = the heavy title-extrabold (was the old default — too thick for some), "Regular".
    public static final Setting<String> LYRICS_WEIGHT = enumSetting(
            "lyrics_weight", DISPLAY, "Lyric weight",
            "Medium",
            "Regular", "Medium", "Bold"
    );

    public static final Setting<String> LYRICS_TEXT_SIZE = enumSetting(
            "lyrics_text_size", DISPLAY, "Lyric text size (fullscreen)",
            "normal",
            "small", "normal", "large", "xlarge"
    );

    // Independent size for the now-playing in-player live card (separate from fullscreen).
    public static final Setting<String> LIVE_CARD_TEXT_SIZE = enumSetting(
            "lyrics_live_card_text_size", DISPLAY, "Now-playing lyric size",
            "normal",
            "small", "normal", "large", "xlarge"
    );

    public static final Setting<String> INTERLUDE_ICON = enumSetting(
            "lyric_interlude_icon", DISPLAY, "Interlude indicator", "dots",
            "dots", "note"
    );

    // "Gradient wash" = the karaoke fill sweeps each line (classic Spicy look).
    // "Spotlight" = no fill; the active line/word zooms + glows instead (gradient direction ignored).
    public static final Setting<String> ANIMATION_STYLE = enumSetting(
            "lyric_animation_style", DISPLAY, "Animation style",
            "Gradient wash",
            "Gradient wash", "Spotlight"
    );

    // Direction the karaoke gradient fills each line as it plays: down the line ("Top to bottom")
    // or word-by-word ("Left to right"). Applies under "Gradient wash" only.
    public static final Setting<String> LINE_SYNC_FILL = enumSetting(
            "lyric_line_sync_fill", DISPLAY, "Lyric fill direction",
            "Top to bottom",
            "Top to bottom", "Left to right"
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
            "native_spicy_romanization", "Enable Spicy romanization", true
    );

    public static final Setting<Boolean> NATIVE_SPICY_TRANSLATION = internalBoolSetting(
            "native_spicy_translation", "Enable Spicy translation", true
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

    public static final Setting<Boolean> ENABLE_LINE_BLUR = internalBoolSetting(
            "lyric_enable_line_blur", "Line distance blur", true
    );

    public static final Setting<String> LYRICS_FONT = internalEnumSetting(
            "lyrics_font", "Lyrics font",
            "default",
            "default", "spotify", "apple"
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

    private static Setting<Boolean> boolSetting(String key, Section section, String label, boolean defaultValue) {
        return new BooleanSetting(key, section, label, defaultValue);
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
