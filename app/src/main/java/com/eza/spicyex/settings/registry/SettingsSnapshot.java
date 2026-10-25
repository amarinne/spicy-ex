package com.eza.spicyex.settings.registry;

import com.eza.spicyex.Settings;
import com.eza.spicyex.settings.SettingsReader;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** Immutable typed values, already resolved for the current orientation by the store. */
public final class SettingsSnapshot implements SettingsReader {
    private final Map<String, Object> values;

    public SettingsSnapshot(Map<String, ?> values) {
        this.values = Collections.unmodifiableMap(new HashMap<>(values));
    }

    public static SettingsSnapshot capture(SettingsReader reader) {
        Map<String, Object> values = new HashMap<>();
        for (Settings.Setting<?> setting : Settings.ALL) values.put(setting.key, reader.get(setting));
        return new SettingsSnapshot(values);
    }

    @Override
    public <T> T get(Settings.Setting<T> setting) {
        return setting.coerce(values.get(setting.key));
    }
}
