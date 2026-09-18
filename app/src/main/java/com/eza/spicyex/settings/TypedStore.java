package com.eza.spicyex.settings;

import com.eza.spicyex.Settings;

/**
 * Minimal typed surface the panel writes through.
 *
 * <p>Implemented by {@code SettingsStore}; JVM tests substitute an in-memory fake. Reads keep
 * flowing through the same surface so snapshots and writers observe one store.
 */
public interface TypedStore {
    <T> T get(Settings.Setting<T> setting);

    boolean contains(Settings.Setting<?> setting);

    void putBoolean(Settings.BooleanSetting setting, boolean value);

    void putString(Settings.StringSetting setting, String value);

    void putInt(Settings.IntegerSetting setting, int value);
}
