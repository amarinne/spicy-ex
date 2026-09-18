package com.eza.spicyex.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.eza.spicyex.Settings;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/** Writer routing and background-style migration over an in-memory store. */
public class SettingsWriterTest {
    static final class FakeStore implements TypedStore {
        final Map<String, Object> values = new HashMap<>();

        @Override
        @SuppressWarnings("unchecked")
        public <T> T get(Settings.Setting<T> setting) {
            Object value = values.get(setting.key);
            return value == null ? setting.defaultValue : (T) value;
        }

        @Override
        public boolean contains(Settings.Setting<?> setting) {
            return values.containsKey(setting.key);
        }

        @Override
        public void putBoolean(Settings.BooleanSetting setting, boolean value) {
            values.put(setting.key, value);
        }

        @Override
        public void putString(Settings.StringSetting setting, String value) {
            values.put(setting.key, value);
        }

        @Override
        public void putInt(Settings.IntegerSetting setting, int value) {
            values.put(setting.key, value);
        }
    }

    @Test
    public void ordinaryWritesLandByKind() {
        FakeStore store = new FakeStore();
        SettingsWriter writer = new SettingsWriter(store);
        writer.put(Settings.TRANSLATION_ENABLED, true);
        writer.put(Settings.TAP_SEEK_MODE, "Single tap");
        writer.put(Settings.SYNC_OFFSET_MS, 250);
        assertEquals(Boolean.TRUE, store.values.get(Settings.TRANSLATION_ENABLED.key));
        assertEquals("Single tap", store.values.get(Settings.TAP_SEEK_MODE.key));
        assertEquals(250, store.values.get(Settings.SYNC_OFFSET_MS.key));
    }

    @Test
    public void migrationWritesStyleOnce() {
        FakeStore store = new FakeStore();
        SettingsWriter writer = new SettingsWriter(store);
        writer.ensureBackgroundStyleMigrated(true);
        assertEquals("Animated texture", store.values.get(Settings.BACKGROUND_STYLE.key));
        writer.put(Settings.BACKGROUND_STYLE, "Gradient");
        writer.ensureBackgroundStyleMigrated(true);
        assertEquals("Gradient", store.values.get(Settings.BACKGROUND_STYLE.key));
    }

    @Test
    public void migrationMapsLegacyFlag() {
        FakeStore store = new FakeStore();
        new SettingsWriter(store).ensureBackgroundStyleMigrated(false);
        assertEquals("Gradient", store.values.get(Settings.BACKGROUND_STYLE.key));
        assertFalse(store.values.containsKey(Settings.ENABLE_BACKGROUND.key));
    }
}
