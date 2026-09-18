package com.eza.spicyex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import android.content.SharedPreferences;

import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class LineBlurMigrationTest {
    @Test
    public void storedTrueKeepsLegacyLookAsSlight() {
        FakePrefs prefs = new FakePrefs();
        prefs.values.put(Settings.ENABLE_LINE_BLUR.key, true);
        SettingsStore.migrateLineBlurLevel(prefs);
        assertEquals("Slight", prefs.values.get(Settings.ENABLE_LINE_BLUR.key));
    }

    @Test
    public void storedFalseBecomesOff() {
        FakePrefs prefs = new FakePrefs();
        prefs.values.put(Settings.ENABLE_LINE_BLUR.key, false);
        SettingsStore.migrateLineBlurLevel(prefs);
        assertEquals("Off", prefs.values.get(Settings.ENABLE_LINE_BLUR.key));
    }

    @Test
    public void migratedStringPassesThrough() {
        FakePrefs prefs = new FakePrefs();
        prefs.values.put(Settings.ENABLE_LINE_BLUR.key, "Heavy");
        SettingsStore.migrateLineBlurLevel(prefs);
        assertEquals("Heavy", prefs.values.get(Settings.ENABLE_LINE_BLUR.key));
    }

    @Test
    public void absentKeyWritesNothing() {
        FakePrefs prefs = new FakePrefs();
        SettingsStore.migrateLineBlurLevel(prefs);
        assertFalse(prefs.values.containsKey(Settings.ENABLE_LINE_BLUR.key));
    }

    static final class FakePrefs implements SharedPreferences {
        final Map<String, Object> values = new HashMap<>();

        @Override public Map<String, ?> getAll() {
            return Collections.unmodifiableMap(values);
        }

        @Override public String getString(String key, String defValue) {
            Object value = values.get(key);
            return value instanceof String ? (String) value : defValue;
        }

        @Override public int getInt(String key, int defValue) {
            Object value = values.get(key);
            return value instanceof Integer ? (Integer) value : defValue;
        }

        @Override public long getLong(String key, long defValue) {
            Object value = values.get(key);
            return value instanceof Long ? (Long) value : defValue;
        }

        @Override public float getFloat(String key, float defValue) {
            Object value = values.get(key);
            return value instanceof Float ? (Float) value : defValue;
        }

        @Override public boolean getBoolean(String key, boolean defValue) {
            Object value = values.get(key);
            return value instanceof Boolean ? (Boolean) value : defValue;
        }

        @Override public Set<String> getStringSet(String key, Set<String> defValues) {
            return defValues;
        }

        @Override public boolean contains(String key) {
            return values.containsKey(key);
        }

        @Override public Editor edit() {
            return new Editor() {
                @Override public Editor putString(String key, String value) {
                    values.put(key, value);
                    return this;
                }

                @Override public Editor putStringSet(String key, Set<String> values) {
                    return this;
                }

                @Override public Editor putInt(String key, int value) {
                    values.put(key, value);
                    return this;
                }

                @Override public Editor putLong(String key, long value) {
                    values.put(key, value);
                    return this;
                }

                @Override public Editor putFloat(String key, float value) {
                    values.put(key, value);
                    return this;
                }

                @Override public Editor putBoolean(String key, boolean value) {
                    values.put(key, value);
                    return this;
                }

                @Override public Editor remove(String key) {
                    values.remove(key);
                    return this;
                }

                @Override public Editor clear() {
                    values.clear();
                    return this;
                }

                @Override public boolean commit() {
                    return true;
                }

                @Override public void apply() {
                }
            };
        }

        @Override public void registerOnSharedPreferenceChangeListener(
                OnSharedPreferenceChangeListener listener) {
        }

        @Override public void unregisterOnSharedPreferenceChangeListener(
                OnSharedPreferenceChangeListener listener) {
        }
    }
}
