package com.eza.spicyex;

import android.content.Context;
import android.content.SharedPreferences;

import com.eza.spicyex.lyrics.language.LanguageModelPack;
import com.eza.spicyex.settings.TypedStore;

import java.util.Map;

/**
 * Owns typed writes and raw schema-coerced reads for the in-Spotify settings panel.
 * Does not own setting defaults (Settings) or surface-specific normalization (LyricsShellSettings).
 * Runs in Spotify's process, so writes land in Spotify-side prefs that SpotifyPlusConfig reads directly.
 */
public final class SettingsStore implements TypedStore {
    private final SharedPreferences prefs;
    private final Context context;

    public SettingsStore(Context context) {
        // NB: not getApplicationContext() — it's null during Application.attach on the hook path.
        this(context.getSharedPreferences(SpotifyPlusConfig.PREFS_NAME, Context.MODE_PRIVATE), context);
    }

    SettingsStore(SharedPreferences prefs, Context context) {
        this.prefs = prefs;
        this.context = context;
        if (context != null) {
            com.eza.spicyex.lyrics.language.LanguageModelPack.attachContext(context);
        }
        migrateLikedSongsButton(prefs);
        migrateLineBlurLevel(prefs);
        migratePanelMediaControls(prefs);
        migrateRemovedAppleCjkWrapFix(prefs);
        migrateRemovedFollowChipToggles(prefs);
        migrateStatusBarHiddenMode(prefs);
        migrateFullscreenControls(prefs);
    }

    static synchronized void migrateLikedSongsButton(SharedPreferences prefs) {
        if (prefs.contains(Settings.LIKED_SONGS_BUTTON.key)) return;
        boolean hasShow = prefs.contains(Settings.LEGACY_SHOW_SAVE_BUTTON);
        boolean hasIcon = prefs.contains(Settings.LEGACY_SAVE_BUTTON_ICON);
        if (!hasShow && !hasIcon) return;
        Map<String, ?> values = prefs.getAll();
        Object show = hasShow ? values.get(Settings.LEGACY_SHOW_SAVE_BUTTON) : Boolean.TRUE;
        Object icon = hasIcon ? values.get(Settings.LEGACY_SAVE_BUTTON_ICON) : "Heart";
        String mode = Settings.LIKED_SONGS_BUTTON.defaultValue;
        if (Boolean.TRUE.equals(show) && ("Heart".equals(icon) || "Star".equals(icon))) {
            mode = (String) icon;
        }
        prefs.edit().putString(Settings.LIKED_SONGS_BUTTON.key, mode).apply();
    }

    /**
     * Bool-to-enum migration for the panel media controls: stored {@code true} keeps the
     * tap-reveal behavior as {@code Single tap}, {@code false} becomes {@code Off}.
     * Already-migrated strings pass through.
     */
    static synchronized void migratePanelMediaControls(SharedPreferences prefs) {
        if (!prefs.contains(Settings.PANEL_MEDIA_CONTROLS.key)) return;
        Object raw = prefs.getAll().get(Settings.PANEL_MEDIA_CONTROLS.key);
        if (raw instanceof String) return;
        boolean on = Boolean.TRUE.equals(raw);
        prefs.edit().putString(Settings.PANEL_MEDIA_CONTROLS.key, on ? "Single tap" : "Off").apply();
    }

    static final String REMOVED_APPLE_CJK_WRAP_FIX_KEY = "lyric_apple_cjk_wrap_fix";

    static synchronized void migrateRemovedAppleCjkWrapFix(SharedPreferences prefs) {
        if (!prefs.contains(REMOVED_APPLE_CJK_WRAP_FIX_KEY)) return;
        prefs.edit().remove(REMOVED_APPLE_CJK_WRAP_FIX_KEY).apply();
    }

    static final String REMOVED_FOLLOW_CHIP_ANIMATION_KEY = "lyric_follow_chip_animation";
    static final String REMOVED_FOLLOW_CHIP_PROGRESS_KEY = "lyric_follow_chip_progress";

    static synchronized void migrateRemovedFollowChipToggles(SharedPreferences prefs) {
        SharedPreferences.Editor editor = null;
        if (prefs.contains(REMOVED_FOLLOW_CHIP_ANIMATION_KEY)) {
            editor = prefs.edit().remove(REMOVED_FOLLOW_CHIP_ANIMATION_KEY);
        }
        if (prefs.contains(REMOVED_FOLLOW_CHIP_PROGRESS_KEY)) {
            editor = (editor != null ? editor : prefs.edit()).remove(REMOVED_FOLLOW_CHIP_PROGRESS_KEY);
        }
        if (editor != null) editor.apply();
    }

    /** Keeps the existing string preference while canonicalizing the retired preset wording. */
    static synchronized void migrateFullscreenControls(SharedPreferences prefs) {
        if (!prefs.contains(Settings.FULLSCREEN_CONTROLS.key)) return;
        Object raw = prefs.getAll().get(Settings.FULLSCREEN_CONTROLS.key);
        if (!(raw instanceof String)) {
            prefs.edit().remove(Settings.FULLSCREEN_CONTROLS.key).apply();
            return;
        }
        String normalized = com.eza.spicyex.lyrics.LyricsShellSettings
                .fullscreenControlsValue(
                        com.eza.spicyex.lyrics.LyricsShellSettings
                                .parseFullscreenControlsSeconds((String) raw));
        if (!normalized.equals(raw)) {
            prefs.edit().putString(Settings.FULLSCREEN_CONTROLS.key, normalized).apply();
        }
    }

    /**
     * Bool-to-enum migration for the blur level: stored {@code true} keeps the legacy look as
     * {@code Slight}, {@code false} becomes {@code Off}. Already-migrated strings pass through.
     */
    static synchronized void migrateLineBlurLevel(SharedPreferences prefs) {
        if (!prefs.contains(Settings.ENABLE_LINE_BLUR.key)) return;
        Object raw = prefs.getAll().get(Settings.ENABLE_LINE_BLUR.key);
        if (raw instanceof String) return;
        boolean on = Boolean.TRUE.equals(raw);
        prefs.edit().putString(Settings.ENABLE_LINE_BLUR.key, on ? "Slight" : "Off").apply();
    }

    /**
     * Bool-pair-to-enum migration for the status-bar mode: stored portrait/landscape choices
     * combine into Off/Portrait/Landscape/Both. Already-migrated strings pass through, and the
     * legacy keys are removed.
     */
    static synchronized void migrateStatusBarHiddenMode(SharedPreferences prefs) {
        boolean hasPortrait = prefs.contains(Settings.LEGACY_STATUS_BAR_HIDDEN_PORTRAIT);
        boolean hasLandscape = prefs.contains(Settings.LEGACY_STATUS_BAR_HIDDEN_LANDSCAPE);
        if (!hasPortrait && !hasLandscape) return;
        Map<String, ?> values = prefs.getAll();
        boolean portrait = hasPortrait && Boolean.TRUE.equals(values.get(Settings.LEGACY_STATUS_BAR_HIDDEN_PORTRAIT));
        boolean landscape = hasLandscape && Boolean.TRUE.equals(values.get(Settings.LEGACY_STATUS_BAR_HIDDEN_LANDSCAPE));
        SharedPreferences.Editor editor = prefs.edit();
        if (hasPortrait) editor.remove(Settings.LEGACY_STATUS_BAR_HIDDEN_PORTRAIT);
        if (hasLandscape) editor.remove(Settings.LEGACY_STATUS_BAR_HIDDEN_LANDSCAPE);
        if (prefs.contains(Settings.STATUS_BAR_HIDDEN_MODE.key)) {
            editor.apply();
            return;
        }
        String mode = Settings.STATUS_BAR_HIDDEN_MODE.defaultValue;
        if (portrait && landscape) {
            mode = "Both";
        } else if (portrait) {
            mode = "Portrait";
        } else if (landscape) {
            mode = "Landscape";
        }
        editor.putString(Settings.STATUS_BAR_HIDDEN_MODE.key, mode).apply();
    }

    public <T> T get(Settings.Setting<T> setting) {
        try {
            String landscapeKey = Settings.landscapeKey(context, setting);
            String key = landscapeKey != null && prefs.contains(landscapeKey)
                    ? landscapeKey : setting.key;
            return setting.coerce(readRaw(key, setting));
        } catch (ClassCastException | IllegalArgumentException invalidStoredValue) {
            return setting.defaultValue;
        }
    }

    private Object readRaw(String key, Settings.Setting<?> setting) {
        if (setting instanceof Settings.BooleanSetting) {
            return prefs.getBoolean(key, (Boolean) setting.defaultValue);
        } else if (setting instanceof Settings.StringSetting) {
            return prefs.getString(key, (String) setting.defaultValue);
        } else if (setting instanceof Settings.IntegerSetting) {
            return prefs.getInt(key, (Integer) setting.defaultValue);
        } else {
            return prefs.getAll().get(key);
        }
    }

    public boolean contains(Settings.Setting<?> setting) {
        return setting != null && prefs.contains(setting.key);
    }

    @Override
    public void putBoolean(Settings.BooleanSetting setting, boolean value) {
        if (setting == Settings.DOWNLOAD_LANGUAGE_MODELS) {
            // The row is intentionally a tap-to-download action rather than a persisted toggle.
            return;
        }
        prefs.edit().putBoolean(storageKey(setting), value).apply();
    }

    @Override
    public void putString(Settings.StringSetting setting, String value) {
        prefs.edit().putString(storageKey(setting), value).apply();
    }

    @Override
    public void putInt(Settings.IntegerSetting setting, int value) {
        prefs.edit().putInt(storageKey(setting), value).apply();
    }

    /** Landscape edits of a layout-fit setting land on its landscape key; see Settings. */
    private String storageKey(Settings.Setting<?> setting) {
        String landscapeKey = Settings.landscapeKey(context, setting);
        return landscapeKey != null ? landscapeKey : setting.key;
    }

    public <T> void put(Settings.Setting<T> setting, T value) {
        String key = storageKey(setting);
        SharedPreferences.Editor editor = prefs.edit();
        if (value instanceof Boolean) {
            editor.putBoolean(key, (Boolean) value);
        } else if (value instanceof String) {
            editor.putString(key, (String) value);
        } else if (value instanceof Integer) {
            editor.putInt(key, (Integer) value);
        } else if (value instanceof Long) {
            editor.putLong(key, (Long) value);
        }
        editor.apply();
    }

    public void putAll(Map<String, ?> values) {
        SharedPreferences.Editor editor = prefs.edit();
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Boolean) {
                editor.putBoolean(entry.getKey(), (Boolean) value);
            } else if (value instanceof String) {
                editor.putString(entry.getKey(), (String) value);
            } else if (value instanceof Integer) {
                editor.putInt(entry.getKey(), (Integer) value);
            } else if (value instanceof Long) {
                editor.putLong(entry.getKey(), (Long) value);
            }
        }
        editor.apply();
    }
}
