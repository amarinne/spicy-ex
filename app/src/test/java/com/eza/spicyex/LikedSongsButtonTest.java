package com.eza.spicyex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.SharedPreferences;

import com.eza.spicyex.ui.ActionIconDrawable;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class LikedSongsButtonTest {
    @Test
    public void defaultsOffWithThreeOptions() {
        // "Plus" retired per owner decision 2026-09-17; it never shipped on private main,
        // so there is no stored-Plus migration path to guard.
        assertEquals("Off", Settings.LIKED_SONGS_BUTTON.defaultValue);
        assertEquals(Arrays.asList("Off", "Heart", "Star"),
                Settings.LIKED_SONGS_BUTTON.allowedValues);
        assertEquals("Off", Settings.LIKED_SONGS_BUTTON.coerce("bogus"));
        assertEquals("Off", Settings.LIKED_SONGS_BUTTON.coerce(null));
        assertEquals("Heart", Settings.LIKED_SONGS_BUTTON.coerce("Heart"));
    }

    @Test
    public void iconMappingMatchesLucideKinds() {
        assertEquals(null, ActionIconDrawable.likedSongsKind("Off"));
        assertEquals(ActionIconDrawable.Kind.HEART, ActionIconDrawable.likedSongsKind("Heart"));
        assertEquals(ActionIconDrawable.Kind.STAR, ActionIconDrawable.likedSongsKind("Star"));
        assertEquals(null, ActionIconDrawable.likedSongsKind("bogus"));
    }

    @Test
    public void migratesLegacyShowAndIcon() {
        FakePrefs prefs = new FakePrefs();
        prefs.values.put(Settings.LEGACY_SHOW_SAVE_BUTTON, true);
        prefs.values.put(Settings.LEGACY_SAVE_BUTTON_ICON, "Star");
        SettingsStore.migrateLikedSongsButton(prefs);
        assertEquals("Star", prefs.values.get(Settings.LIKED_SONGS_BUTTON.key));
    }

    @Test
    public void legacyHiddenStaysOff() {
        FakePrefs prefs = new FakePrefs();
        prefs.values.put(Settings.LEGACY_SHOW_SAVE_BUTTON, false);
        prefs.values.put(Settings.LEGACY_SAVE_BUTTON_ICON, "Heart");
        SettingsStore.migrateLikedSongsButton(prefs);
        assertEquals("Off", prefs.values.get(Settings.LIKED_SONGS_BUTTON.key));
    }

    @Test
    public void noLegacyPrefsWritesNothing() {
        FakePrefs prefs = new FakePrefs();
        SettingsStore.migrateLikedSongsButton(prefs);
        assertFalse(prefs.values.containsKey(Settings.LIKED_SONGS_BUTTON.key));
    }

    @Test
    public void existingEnumIsNeverOverwritten() {
        FakePrefs prefs = new FakePrefs();
        prefs.values.put(Settings.LIKED_SONGS_BUTTON.key, "Heart");
        prefs.values.put(Settings.LEGACY_SHOW_SAVE_BUTTON, true);
        prefs.values.put(Settings.LEGACY_SAVE_BUTTON_ICON, "Star");
        SettingsStore.migrateLikedSongsButton(prefs);
        assertEquals("Heart", prefs.values.get(Settings.LIKED_SONGS_BUTTON.key));
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
