package com.eza.spicyex;

import android.content.Context;
import android.content.SharedPreferences;

import com.eza.spicyex.settings.TypedStore;

import java.util.Map;

/**
 * Owns typed writes and raw schema-coerced reads for the in-Spotify settings panel.
 * Does not own setting defaults (Settings) or surface-specific normalization (LyricsShellSettings).
 * Runs in Spotify's process, so writes land in Spotify-side prefs that SpotifyPlusConfig reads directly.
 */
public final class SettingsStore implements TypedStore {
    private final SharedPreferences prefs;

    public SettingsStore(Context context) {
        // NB: not getApplicationContext() — it's null during Application.attach on the hook path.
        this(context.getSharedPreferences(SpotifyPlusConfig.PREFS_NAME, Context.MODE_PRIVATE));
    }

    SettingsStore(SharedPreferences prefs) {
        this.prefs = prefs;
        migrateLikedSongsButton(prefs);
        migrateLineBlurLevel(prefs);
        migratePanelMediaControls(prefs);
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

    public <T> T get(Settings.Setting<T> setting) {
        try {
            Object value;
            if (setting instanceof Settings.BooleanSetting) {
                value = prefs.getBoolean(setting.key, (Boolean) setting.defaultValue);
            } else if (setting instanceof Settings.StringSetting) {
                value = prefs.getString(setting.key, (String) setting.defaultValue);
            } else if (setting instanceof Settings.IntegerSetting) {
                value = prefs.getInt(setting.key, (Integer) setting.defaultValue);
            } else {
                value = prefs.getAll().get(setting.key);
            }
            return setting.coerce(value);
        } catch (ClassCastException | IllegalArgumentException invalidStoredValue) {
            return setting.defaultValue;
        }
    }

    public boolean contains(Settings.Setting<?> setting) {
        return setting != null && prefs.contains(setting.key);
    }

    @Override
    public void putBoolean(Settings.BooleanSetting setting, boolean value) {
        prefs.edit().putBoolean(setting.key, value).apply();
    }

    @Override
    public void putString(Settings.StringSetting setting, String value) {
        prefs.edit().putString(setting.key, value).apply();
    }

    @Override
    public void putInt(Settings.IntegerSetting setting, int value) {
        prefs.edit().putInt(setting.key, value).apply();
    }

    public <T> void put(Settings.Setting<T> setting, T value) {
        SharedPreferences.Editor editor = prefs.edit();
        if (value instanceof Boolean) {
            editor.putBoolean(setting.key, (Boolean) value);
        } else if (value instanceof String) {
            editor.putString(setting.key, (String) value);
        } else if (value instanceof Integer) {
            editor.putInt(setting.key, (Integer) value);
        } else if (value instanceof Long) {
            editor.putLong(setting.key, (Long) value);
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
