package com.eza.spicyex.testsupport;

import android.content.ContextWrapper;
import android.content.SharedPreferences;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A {@link android.content.Context} that serves in-memory preference files.
 *
 * <p>The lane, the settings store, and the paid-record store each read their own file, so the
 * fake keeps one map per file name rather than one shared map that would let a key from one
 * store collide with another's.
 */
public final class FakeAndroidContext extends ContextWrapper {

    private final Map<String, MapPrefs> files = new HashMap<>();
    private File filesDir;

    public FakeAndroidContext() {
        super(null);
    }

    public MapPrefs file(String name) {
        MapPrefs existing = files.get(name);
        if (existing != null) return existing;
        MapPrefs created = new MapPrefs();
        files.put(name, created);
        return created;
    }

    @Override public SharedPreferences getSharedPreferences(String name, int mode) {
        return file(name == null ? "" : name);
    }

    @Override public ContextWrapper getApplicationContext() {
        return this;
    }

    /**
     * A real temporary directory, because production code hands this context to process-wide
     * singletons that keep it: a stub that threw here would leak into every later test in the JVM.
     */
    @Override public File getFilesDir() {
        if (filesDir == null) {
            try {
                filesDir = Files.createTempDirectory("spicyex-fake-context").toFile();
            } catch (IOException unreachable) {
                throw new UncheckedIOException(unreachable);
            }
            File created = filesDir;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> delete(created)));
        }
        return filesDir;
    }

    @Override public File getCacheDir() {
        return getFilesDir();
    }

    private static void delete(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) delete(child);
        }
        file.delete();
    }

    public static final class MapPrefs implements SharedPreferences {
        private final Map<String, Object> values = new HashMap<>();

        @Override public Map<String, ?> getAll() {
            return Collections.unmodifiableMap(new HashMap<>(values));
        }

        @Override public String getString(String key, String defValue) {
            Object value = values.get(key);
            return value instanceof String ? (String) value : defValue;
        }

        @Override public Set<String> getStringSet(String key, Set<String> defValues) {
            Object value = values.get(key);
            if (!(value instanceof Set)) return defValues;
            Set<String> copy = new HashSet<>();
            for (Object item : (Set<?>) value) {
                if (item instanceof String) copy.add((String) item);
            }
            return copy;
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

        @Override public boolean contains(String key) {
            return values.containsKey(key);
        }

        @Override public void registerOnSharedPreferenceChangeListener(
                OnSharedPreferenceChangeListener listener) {
        }

        @Override public void unregisterOnSharedPreferenceChangeListener(
                OnSharedPreferenceChangeListener listener) {
        }

        @Override public Editor edit() {
            return new MapEditor();
        }

        private final class MapEditor implements Editor {
            private final Map<String, Object> staged = new HashMap<>();
            private final List<String> removals = new ArrayList<>();
            private boolean clear;

            @Override public Editor putString(String key, String value) {
                staged.put(key, value);
                return this;
            }

            @Override public Editor putStringSet(String key, Set<String> value) {
                staged.put(key, value == null ? null : new HashSet<>(value));
                return this;
            }

            @Override public Editor putInt(String key, int value) {
                staged.put(key, value);
                return this;
            }

            @Override public Editor putLong(String key, long value) {
                staged.put(key, value);
                return this;
            }

            @Override public Editor putFloat(String key, float value) {
                staged.put(key, value);
                return this;
            }

            @Override public Editor putBoolean(String key, boolean value) {
                staged.put(key, value);
                return this;
            }

            @Override public Editor remove(String key) {
                removals.add(key);
                return this;
            }

            @Override public Editor clear() {
                clear = true;
                return this;
            }

            @Override public boolean commit() {
                apply();
                return true;
            }

            @Override public void apply() {
                if (clear) values.clear();
                for (String key : removals) values.remove(key);
                for (Map.Entry<String, Object> entry : staged.entrySet()) {
                    if (entry.getValue() == null) values.remove(entry.getKey());
                    else values.put(entry.getKey(), entry.getValue());
                }
            }
        }
    }
}
