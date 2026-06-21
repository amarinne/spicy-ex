package com.eza.spicyex;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.Map;

/**
 * Typed read/write access to the "SpotifyPlus" prefs. Used by the in-Spotify settings panel, which
 * runs in Spotify's process — so writes land in Spotify-side prefs that {@link SpotifyPlusConfig}
 * (the hook) reads directly, no IPC. (The standalone app + broadcast bridge were removed.)
 */
public final class SettingsStore {
    private final SharedPreferences prefs;

    public SettingsStore(Context context) {
        // NB: not getApplicationContext() — it's null during Application.attach on the hook path.
        this.prefs = context.getSharedPreferences(SpotifyPlusConfig.PREFS_NAME, Context.MODE_PRIVATE);
    }

    public <T> T get(Settings.Setting<T> setting) {
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
