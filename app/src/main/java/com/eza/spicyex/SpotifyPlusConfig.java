package com.eza.spicyex;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

/**
 * Owns hook/runtime reads from the host-process "SpotifyPlus" preferences.
 * Does not own panel writes (SettingsStore), schema/defaults (Settings), or UI normalization.
 * Acts as the bridge between Spotify-process prefs and module runtime settings.
 */
public final class SpotifyPlusConfig {
    public static final String PREFS_NAME = "SpotifyPlus";

    // Value constants shared by Settings declarations and normalization helpers. The only raw-key
    // reads left are the source-language/translation-target pair in LyricsTranslator.
    public static final String KEY_SOURCE_LANGUAGE_MODE = "lyrics_source_language_mode";
    public static final String SOURCE_LANGUAGE_AUTO = "auto";
    public static final String SOURCE_LANGUAGE_MANUAL = "manual";
    public static final String KEY_SOURCE_LANGUAGE = "lyrics_source_language";
    public static final String KEY_TRANSLATION_TARGET = "lyrics_translation_target";
    public static final String CHINESE_MODE_PINYIN = "pinyin";
    public static final String CHINESE_MODE_JYUTPING = "jyutping";
    public static final String JP_READING_FURIGANA_ONLY = "furigana_only";
    public static final String JP_READING_FURIGANA_ROMAJI = "furigana_romaji";
    public static final String JP_READING_ROMAJI_ONLY = "romaji_only";

    private final SharedPreferences hostPrefs;
    private final Context appContext;

    private SpotifyPlusConfig(SharedPreferences hostPrefs, Context appContext) {
        this.hostPrefs = hostPrefs;
        this.appContext = appContext;
        SettingsStore.migrateLikedSongsButton(hostPrefs);
        SettingsStore.migrateLineBlurLevel(hostPrefs);
        SettingsStore.migrateSourceRankingLabel(hostPrefs);
        SettingsStore.migrateAdMode(hostPrefs);
    }

    public static SpotifyPlusConfig from(Context context) {
        Context ctx = context.getApplicationContext();
        if (ctx == null) ctx = context;
        return new SpotifyPlusConfig(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE), ctx);
    }

    // Single source of truth: the "SpotifyPlus" prefs in THIS process. In the hook that's Spotify's
    // own prefs, written by the in-Spotify settings panel (same process) — so reads are live and need
    // no IPC. In the (now-removed) standalone app it was that app's prefs.

    public <T> T get(Settings.Setting<T> setting) {
        try {
            String landscapeKey = Settings.landscapeKey(appContext, setting);
            String key = landscapeKey != null && hostPrefs.contains(landscapeKey)
                    ? landscapeKey : setting.key;
            Object value = readRaw(key, setting);
            if (value == null) value = setting.defaultValue;
            return setting.coerce(value);
        } catch (ClassCastException | IllegalArgumentException invalidStoredValue) {
            // A preference may survive a schema type change. Ignore the invalid value and use
            // the declared default; runtime settings must never crash Spotify during startup.
            return setting.defaultValue;
        }
    }

    private Object readRaw(String key, Settings.Setting<?> setting) {
        if (setting instanceof Settings.BooleanSetting) {
            return hostPrefs.getBoolean(key, (Boolean) setting.defaultValue);
        } else if (setting instanceof Settings.IntegerSetting) {
            return hostPrefs.getInt(key, (Integer) setting.defaultValue);
        } else {
            return hostPrefs.getString(key, (String) setting.defaultValue);
        }
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        return hostPrefs.getBoolean(key, defaultValue);
    }

    public String getString(String key, String defaultValue) {
        return hostPrefs.getString(key, defaultValue);
    }

    public boolean contains(Settings.Setting<?> setting) {
        return setting != null && hostPrefs.contains(setting.key);
    }

    // --- High-level accessors ---

    public String lyricsDisplayMode() {
        return get(Settings.DISPLAY_MODE);
    }

    public boolean showTranslationLyrics() {
        // "original_translation" / "original_romanized_translation" — the translation-bearing
        // members of Settings.DISPLAY_MODE's allowed values.
        String mode = lyricsDisplayMode();
        return "original_translation".equals(mode)
                || "original_romanized_translation".equals(mode);
    }

}
