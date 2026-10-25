package com.eza.spicyex.testsupport;

import android.content.ContentResolver;
import android.content.ContextWrapper;
import android.content.SharedPreferences;

import com.eza.hyperglow.bridge.ISpicyLyricBridge;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
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

    /** Recorded bind orders: the bridge publisher's demand signal. Decline matches a Spotify
     *  process that cannot resolve the companion package; an error emulates the throw. */
    public int bindServiceCalls;
    public int unbindServiceCalls;
    public android.content.Intent lastBindIntent;
    /** Default is a decline, like an invisible companion package. */
    public boolean bindServiceShouldSucceed = false;
    /** Raised instead of the decline, e.g. the SecurityException a hidden package yields. */
    public java.lang.RuntimeException bindServiceError = null;
    /** A successful bind opens the connection synchronously with the fake bridge binder. */
    public android.content.ServiceConnection lastServiceConnection;
    /**
     * False models the window inside {@code Application.attach}: the framework has no application
     * context yet, so it keys the ServiceDispatcher by the attaching context. The later unbind,
     * issued after the application context exists, looks under a different key and misses.
     */
    public boolean applicationContextAttached = true;
    /** Connections the platform still holds, i.e. binds an unbind never found. */
    public int liveBindings;
    private final Set<android.content.ServiceConnection> attachingBindings =
            Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private final Set<android.content.ServiceConnection> attachedBindings =
            Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    /** Provider calls the bridge consumer made, in resolver arrival order. */
    public final List<String> providerCalls = new ArrayList<>();
    /**
     * Per-call outcomes: "return" succeeds, "throw" fails, "block" records the call and waits
     * for {@link #providerCallGate} before succeeding. An empty deque throws, like the absent
     * companion the publisher expects to fail against on a plain fake context.
     */
    public final Deque<String> providerCallOutcomes = new ArrayDeque<>();
    /** Gate released by a test to settle a "block" outcome. */
    public java.util.concurrent.CountDownLatch providerCallGate;
    /** State payloads a connected bridge received (the replay witness on the binder path). */
    public final List<android.os.Bundle> publishedStates = new ArrayList<>();
    /** "producerId:generation" strings a connected bridge received via clearState. */
    public final List<String> clearedStates = new ArrayList<>();
    /** Document metadata a connected bridge received via publishDocument. */
    public final List<android.os.Bundle> publishedDocumentMetadata = new ArrayList<>();

    private android.os.IBinder bridgeBinder;

    public FakeAndroidContext() {
        super(null);
    }

    @Override public boolean bindService(android.content.Intent service,
                                         android.content.ServiceConnection connection, int flags) {
        bindServiceCalls++;
        lastBindIntent = service;
        lastServiceConnection = connection;
        if (bindServiceError != null) throw bindServiceError;
        if (!bindServiceShouldSucceed) return false;
        registry().add(connection);
        liveBindings = attachingBindings.size() + attachedBindings.size();
        // A successful bind delivers its connection synchronously here: a fresh lifetime's
        // bridge opens for the test without platform scheduling.
        connection.onServiceConnected(service.getComponent(), bridgeBinder());
        return true;
    }

    @Override public void unbindService(android.content.ServiceConnection connection) {
        unbindServiceCalls++;
        // LoadedApk resolves the dispatcher by the outer context it sees now, so a binding made
        // during attach is never found after the application context is installed.
        registry().remove(connection);
        liveBindings = attachingBindings.size() + attachedBindings.size();
    }

    private Set<android.content.ServiceConnection> registry() {
        return getApplicationContext() == null ? attachingBindings : attachedBindings;
    }

    @Override public android.content.ContentResolver getContentResolver() {
        ContentResolver.testHandler = providerHandler();
        return new ContentResolver(this);
    }

    /** The resolver lane: records every call and answers with the staged outcomes. */
    private android.content.ContentResolver.Handler providerHandler() {
        return (uri, method, arg, extras) -> {
            providerCalls.add(method);
            String outcome = providerCallOutcomes.isEmpty()
                    ? "throw" : providerCallOutcomes.pollFirst();
            if ("return".equals(outcome)) return null;
            if ("block".equals(outcome)) {
                if (providerCallGate != null) {
                    try {
                        if (!providerCallGate.await(
                                5L, java.util.concurrent.TimeUnit.SECONDS)) {
                            throw new IllegalStateException("provider call was never released");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("provider call interrupted while blocked", e);
                    }
                }
                return null;
            }
            throw new RuntimeException("provider stub refused " + method);
        };
    }

    public android.os.IBinder bridgeBinder() {
        if (bridgeBinder == null) {
            bridgeBinder = new ISpicyLyricBridge.Stub() {
                @Override public void publishState(android.os.Bundle state) {
                    publishedStates.add(new android.os.Bundle(state));
                }

                @Override public void publishDocument(android.os.Bundle metadata,
                                                      android.os.ParcelFileDescriptor document) {
                    publishedDocumentMetadata.add(new android.os.Bundle(metadata));
                }

                @Override public void clearState(String producerId, long generation) {
                    clearedStates.add(producerId + ":" + generation);
                }
            };
        }
        return bridgeBinder;
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
        return applicationContextAttached ? this : null;
    }

    /**
     * A real temporary directory, because production code hands this context to process-wide
     * singletons that keep it: a stub that threw here would leak into every later test in the JVM.
     */
    @Override public File getFilesDir() {        if (filesDir == null) {
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
        /** Change notifications the fake delivers on its editor's apply()/commit(). */
        private final java.util.List<OnSharedPreferenceChangeListener> listeners = new ArrayList<>();

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
            if (listener == null || listeners.contains(listener)) return;
            listeners.add(listener);
        }

        @Override public void unregisterOnSharedPreferenceChangeListener(
                OnSharedPreferenceChangeListener listener) {
            listeners.remove(listener);
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

            private List<String> commitLocked() {
                List<String> changed = new ArrayList<>();
                for (String key : removals) {
                    if (values.remove(key) != null) changed.add(key);
                }
                if (clear) {
                    changed.addAll(values.keySet());
                    values.clear();
                }
                for (Map.Entry<String, Object> entry : staged.entrySet()) {
                    Object previous = entry.getValue() == null
                            ? values.remove(entry.getKey()) : values.put(entry.getKey(), entry.getValue());
                    if (previous != entry.getValue()) changed.add(entry.getKey());
                }
                return changed;
            }

            @Override public boolean commit() {
                List<String> changed = commitLocked();
                notifyChanged(changed);
                return true;
            }

            @Override public void apply() {
                List<String> changed = commitLocked();
                notifyChanged(changed);
            }

            /** Callers see one notification per changed key, like the platform SharedPreferences. */
            private void notifyChanged(List<String> changedKeys) {
                if (listeners.isEmpty()) return;
                for (OnSharedPreferenceChangeListener listener : new ArrayList<>(listeners)) {
                    for (String key : changedKeys) {
                        listener.onSharedPreferenceChanged(MapPrefs.this, key);
                    }
                }
            }
        }
    }
}
