package com.eza.spicyex;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Owns hook/runtime reads from the host-process "SpotifyPlus" preferences.
 * Does not own panel writes (SettingsStore), schema/defaults (Settings), or UI normalization.
 * Acts as the bridge between Spotify-process prefs and module runtime settings.
 */
public final class SpotifyPlusConfig {
    public static final String PREFS_NAME = "SpotifyPlus";

    // Legacy keys preserved for backward compatibility at call sites
    public static final String KEY_DISPLAY_MODE = "lyrics_display_mode";
    public static final String DISPLAY_ORIGINAL = "original";
    public static final String DISPLAY_ROMANIZED = "romanized";
    public static final String DISPLAY_ORIGINAL_ROMANIZED = "original_romanized";
    public static final String DISPLAY_ORIGINAL_TRANSLATION = "original_translation";
    public static final String DISPLAY_ORIGINAL_ROMANIZED_TRANSLATION = "original_romanized_translation";

    public static final String KEY_SOURCE_LANGUAGE_MODE = "lyrics_source_language_mode";
    public static final String SOURCE_LANGUAGE_AUTO = "auto";
    public static final String SOURCE_LANGUAGE_MANUAL = "manual";
    public static final String KEY_SOURCE_LANGUAGE = "lyrics_source_language";
    public static final String KEY_CHINESE_MODE = "lyrics_chinese_mode";
    public static final String CHINESE_MODE_PINYIN = "pinyin";
    public static final String CHINESE_MODE_JYUTPING = "jyutping";
    public static final String KEY_TRANSLATION_BACKEND = "lyrics_translation_backend";
    public static final String KEY_TRANSLATION_TARGET = "lyrics_translation_target";
    public static final String KEY_JAPANESE_READING_MODE = "lyrics_japanese_reading_mode";
    public static final String JP_READING_FURIGANA_ONLY = "furigana_only";
    public static final String JP_READING_FURIGANA_ROMAJI = "furigana_romaji";
    public static final String JP_READING_ROMAJI_ONLY = "romaji_only";

    private final SharedPreferences hostPrefs;

    private SpotifyPlusConfig(SharedPreferences hostPrefs) {
        this.hostPrefs = hostPrefs;
    }

    public static SpotifyPlusConfig from(Context context) {
        return new SpotifyPlusConfig(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE));
    }

    // Single source of truth: the "SpotifyPlus" prefs in THIS process. In the hook that's Spotify's
    // own prefs, written by the in-Spotify settings panel (same process) — so reads are live and need
    // no IPC. In the (now-removed) standalone app it was that app's prefs.

    public <T> T get(Settings.Setting<T> setting) {
        Object value;
        if (setting instanceof Settings.BooleanSetting) {
            value = hostPrefs.getBoolean(setting.key, (Boolean) setting.defaultValue);
        } else {
            if (setting instanceof Settings.IntegerSetting) {
                value = hostPrefs.getInt(setting.key, (Integer) setting.defaultValue);
            } else {
                value = hostPrefs.getString(setting.key, (String) setting.defaultValue);
            }
        }
        if (value == null) value = setting.defaultValue;
        return setting.coerce(value);
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        return hostPrefs.getBoolean(key, defaultValue);
    }

    public String getString(String key, String defaultValue) {
        return hostPrefs.getString(key, defaultValue);
    }

    // --- High-level accessors ---

    public String lyricsDisplayMode() {
        return get(Settings.DISPLAY_MODE);
    }

    public boolean showOriginalLyrics() {
        String mode = lyricsDisplayMode();
        return !DISPLAY_ROMANIZED.equals(mode);
    }

    public boolean showRomanizedLyrics() {
        String mode = lyricsDisplayMode();
        return DISPLAY_ROMANIZED.equals(mode)
                || DISPLAY_ORIGINAL_ROMANIZED.equals(mode)
                || DISPLAY_ORIGINAL_ROMANIZED_TRANSLATION.equals(mode);
    }

    public boolean showTranslationLyrics() {
        String mode = lyricsDisplayMode();
        return DISPLAY_ORIGINAL_TRANSLATION.equals(mode)
                || DISPLAY_ORIGINAL_ROMANIZED_TRANSLATION.equals(mode);
    }

    public boolean romanizedOnly() {
        return DISPLAY_ROMANIZED.equals(lyricsDisplayMode());
    }

    public String japaneseReadingMode() {
        return get(Settings.JAPANESE_READING_MODE);
    }
}
