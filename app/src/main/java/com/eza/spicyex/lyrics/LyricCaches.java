package com.eza.spicyex.lyrics;

import android.content.Context;
import android.content.SharedPreferences;

import com.eza.spicyex.Diagnostics;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Collection;
import java.util.Map;
import java.util.Locale;
import static com.eza.spicyex.lyrics.LyricUtils.isBlank;
import static com.eza.spicyex.lyrics.LyricUtils.safe;

/** Preference-backed caches used by native Spicy lyrics processing. */
public final class LyricCaches {
    private static final String PREFS_GOOGLE_CACHE = "SpotifyPlusNativeSpicyGoogleCache";
    private static final String PREFS_GOOGLE_CACHE_ORDER_KEY = "__cache_order";
    private static final String PREFS_PROCESSED_CACHE = "SpotifyPlusNativeSpicyProcessedCache";
    /** Sound artifacts. Separate store so Meaning eviction can never drop a reading artifact. */
    private static final String PREFS_SOUND_CACHE = "SpotifyPlusSoundArtifactCache";
    /** Meaning artifacts. Separate store so a Sound contract bump never discards paid-for work. */
    private static final String PREFS_MEANING_CACHE = "SpotifyPlusMeaningArtifactCache";
    private static final String PREFS_PROCESSED_CACHE_ORDER_KEY = "__cache_order";
    private static final int GOOGLE_CACHE_MAX_ENTRIES = 5000;
    private static final int PROCESSED_CACHE_MAX_ENTRIES = 24;
    private static final long PROCESSED_CACHE_MAX_BYTES = 4L * 1024L * 1024L;
    private static final long PROCESSED_CACHE_MAX_AGE_MS = 7L * 24L * 60L * 60L * 1000L;
    // A track keeps one reading record per configuration, so switching back to a mode you have
    // used before is instant instead of a re-derivation. That is the common case — people settle on
    // a reading style — so the store is sized for several styles across a healthy set of tracks
    // rather than made to hold one record per track. Sized here so those extra records cost other
    // tracks nothing.
    private static final int SOUND_CACHE_MAX_ENTRIES = 160;
    private static final long SOUND_CACHE_MAX_BYTES = 8L * 1024L * 1024L;
    private static final int MEANING_CACHE_MAX_ENTRIES = 96;
    private static final long MEANING_CACHE_MAX_BYTES = 4L * 1024L * 1024L;
    private static final Object GOOGLE_CACHE_LOCK = new Object();
    private static final Object PROCESSED_CACHE_LOCK = new Object();

    private LyricCaches() {
    }

    public static void clearGoogle(Context context) {
        if (context == null) return;
        context.getSharedPreferences(PREFS_GOOGLE_CACHE, Context.MODE_PRIVATE).edit().clear().apply();
    }

    public static void clearProcessed(Context context) {
        if (context == null) return;
        context.getSharedPreferences(PREFS_PROCESSED_CACHE, Context.MODE_PRIVATE).edit().clear().apply();
        context.getSharedPreferences(PREFS_SOUND_CACHE, Context.MODE_PRIVATE).edit().clear().apply();
        context.getSharedPreferences(PREFS_MEANING_CACHE, Context.MODE_PRIVATE).edit().clear().apply();
    }

    /** Drops Sound artifacts only. A Sound contract change must not touch Meaning. */
    public static void clearSoundArtifacts(Context context) {
        if (context == null) return;
        context.getSharedPreferences(PREFS_SOUND_CACHE, Context.MODE_PRIVATE).edit().clear().apply();
    }

    /** Drops Meaning artifacts only. A Meaning contract change must not touch Sound. */
    public static void clearMeaningArtifacts(Context context) {
        if (context == null) return;
        context.getSharedPreferences(PREFS_MEANING_CACHE, Context.MODE_PRIVATE).edit().clear().apply();
    }

    public static String getSoundArtifact(Context context, String key) {
        return getBoundedRecord(context, PREFS_SOUND_CACHE, key,
                SOUND_CACHE_MAX_ENTRIES, SOUND_CACHE_MAX_BYTES);
    }

    public static void putSoundArtifact(Context context, String key, String value) {
        putBoundedRecord(context, PREFS_SOUND_CACHE, key, value,
                SOUND_CACHE_MAX_ENTRIES, SOUND_CACHE_MAX_BYTES);
    }

    public static String getMeaningArtifact(Context context, String key) {
        return getBoundedRecord(context, PREFS_MEANING_CACHE, key,
                MEANING_CACHE_MAX_ENTRIES, MEANING_CACHE_MAX_BYTES);
    }

    public static void putMeaningArtifact(Context context, String key, String value) {
        putBoundedRecord(context, PREFS_MEANING_CACHE, key, value,
                MEANING_CACHE_MAX_ENTRIES, MEANING_CACHE_MAX_BYTES);
    }

    public static String sourceLanguageForCache(String sourceLang) {
        return isBlank(sourceLang) || "unknown".equalsIgnoreCase(sourceLang)
                ? "auto"
                : SpicyProcessing.toIso2(sourceLang);
    }

    public static String romanizationKey(String trackId, String sourceLang, String text) {
        return "romanize|" + safe(trackId) + "|" + sourceLanguageForCache(sourceLang) + "|" + safe(text);
    }

    public static String translationKey(String trackId, String sourceLang, String targetLang, String text) {
        return "translate|" + safe(trackId) + "|" + sourceLanguageForCache(sourceLang) + "|" + safe(targetLang) + "|" + safe(text);
    }

    private static String getBoundedRecord(Context context, String prefsName, String key) {
        return getBoundedRecord(context, prefsName, key,
                PROCESSED_CACHE_MAX_ENTRIES, PROCESSED_CACHE_MAX_BYTES);
    }

    private static String getBoundedRecord(Context context, String prefsName, String key,
                                           int maxEntries, long maxBytes) {
        if (context == null) return null;
        try {
            SharedPreferences prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE);
            String hashedKey = sha256(key);
            synchronized (PROCESSED_CACHE_LOCK) {
                String value = prefs.getString(hashedKey, null);
                if (value == null) return null;
                ProcessedCacheOrderUpdate update = boundedProcessedCacheOrder(
                        prefs.getString(PREFS_PROCESSED_CACHE_ORDER_KEY, ""), hashedKey,
                        value.getBytes(StandardCharsets.UTF_8).length, System.currentTimeMillis(),
                        maxEntries, maxBytes, PROCESSED_CACHE_MAX_AGE_MS);
                SharedPreferences.Editor editor = prefs.edit();
                for (String evicted : update.evictedKeys) editor.remove(evicted);
                String nextOrder = update.evictedKeys.contains(hashedKey)
                        ? removeProcessedOrderEntry(update.nextOrder, hashedKey)
                        : update.nextOrder;
                if (!update.evictedKeys.isEmpty() || update.changed) {
                    editor.putString(PREFS_PROCESSED_CACHE_ORDER_KEY, nextOrder).apply();
                }
                return update.evictedKeys.contains(hashedKey) ? null : value;
            }
        } catch (Throwable t) {
            Diagnostics.warn("LyricCaches", "getBoundedRecord", t);
            return null;
        }
    }

    private static void putBoundedRecord(Context context, String prefsName, String key, String value) {
        putBoundedRecord(context, prefsName, key, value,
                PROCESSED_CACHE_MAX_ENTRIES, PROCESSED_CACHE_MAX_BYTES);
    }

    private static void putBoundedRecord(Context context, String prefsName, String key, String value,
                                         int maxEntries, long maxBytes) {
        if (context == null || isBlank(value)) return;
        try {
            SharedPreferences prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE);
            String hashedKey = sha256(key);
            synchronized (PROCESSED_CACHE_LOCK) {
                ProcessedCacheOrderUpdate update = boundedProcessedCacheOrder(
                        prefs.getString(PREFS_PROCESSED_CACHE_ORDER_KEY, ""), hashedKey,
                        value.getBytes(StandardCharsets.UTF_8).length, System.currentTimeMillis(),
                        maxEntries, maxBytes, PROCESSED_CACHE_MAX_AGE_MS);
                SharedPreferences.Editor editor = prefs.edit();
                if (update.evictedKeys.contains(hashedKey)) editor.remove(hashedKey);
                else editor.putString(hashedKey, value);
                for (String evicted : update.evictedKeys) {
                    if (!hashedKey.equals(evicted)) editor.remove(evicted);
                }
                String nextOrder = update.evictedKeys.contains(hashedKey)
                        ? removeProcessedOrderEntry(update.nextOrder, hashedKey)
                        : update.nextOrder;
                editor.putString(PREFS_PROCESSED_CACHE_ORDER_KEY, nextOrder).apply();
            }
        } catch (Throwable t) {
            Diagnostics.warn("LyricCaches", "putBoundedRecord", t);
        }
    }

    /**
     * Sound artifact key: canonical digest plus Sound configuration only. No translation backend,
     * target language, or Meaning contract may appear here.
     *
     * <p>Keeping the configuration in the key means a track holds one record per reading style it
     * has been shown in, so returning to a style is instant rather than a re-derivation. The store
     * is sized for that; see {@code SOUND_CACHE_MAX_ENTRIES}.
     */
    public static String soundArtifactKey(String canonicalDigest, String soundConfigId) {
        return "sound|" + safe(canonicalDigest) + "|" + safe(soundConfigId);
    }

    /**
     * Meaning artifact key: canonical digest plus Meaning configuration only. No romanization
     * option or reading contract may appear here.
     */
    public static String meaningArtifactKey(String canonicalDigest, String meaningConfigId) {
        return "meaning|" + safe(canonicalDigest) + "|" + safe(meaningConfigId);
    }

    public static String getProcessingValue(Context context, int processingVersion, String key) {
        String versionedKey = processingCacheKey(processingVersion, key);
        String value = getGoogleValue(context, versionedKey);
        if (value != null) return value;
        String legacy = getGoogleValue(context, key);
        if (legacy != null) putGoogleValue(context, versionedKey, legacy);
        return legacy;
    }

    public static void putProcessingValue(Context context, int processingVersion, String key, String value) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(key, value);
        putProcessingValues(context, processingVersion, values);
    }

    public static void putProcessingValues(Context context, int processingVersion,
                                           Map<String, String> values) {
        if (context == null || values == null || values.isEmpty()) return;
        Map<String, String> versioned = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (entry == null || isBlank(entry.getValue())) continue;
            versioned.put(processingCacheKey(processingVersion, entry.getKey()), entry.getValue());
        }
        putGoogleValues(context, versioned);
    }

    private static String processingCacheKey(int processingVersion, String key) {
        return "native-spicy-processing-v" + processingVersion + "|" + safe(key);
    }

    private static String getGoogleValue(Context context, String key) {
        if (context == null) return null;
        try {
            return context.getSharedPreferences(PREFS_GOOGLE_CACHE, Context.MODE_PRIVATE).getString(sha256(key), null);
        } catch (Throwable t) {
            Diagnostics.warn("LyricCaches", "getGoogleValue", t);
            return null;
        }
    }

    private static void putGoogleValue(Context context, String key, String value) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(key, value);
        putGoogleValues(context, values);
    }

    private static void putGoogleValues(Context context, Map<String, String> values) {
        if (context == null || values == null || values.isEmpty()) return;
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_GOOGLE_CACHE, Context.MODE_PRIVATE);
            synchronized (GOOGLE_CACHE_LOCK) {
                SharedPreferences.Editor editor = prefs.edit();
                LinkedHashSet<String> hashedKeys = new LinkedHashSet<>();
                for (Map.Entry<String, String> entry : values.entrySet()) {
                    if (entry == null || isBlank(entry.getValue())) continue;
                    String hashedKey = sha256(entry.getKey());
                    hashedKeys.add(hashedKey);
                    editor.putString(hashedKey, entry.getValue());
                }
                if (hashedKeys.isEmpty()) return;
                recordBoundedGoogleCachePut(prefs, editor, hashedKeys);
                editor.apply();
            }
        } catch (Throwable t) {
            Diagnostics.warn("LyricCaches", "putGoogleValue", t);
        }
    }

    private static void recordBoundedGoogleCachePut(SharedPreferences prefs, SharedPreferences.Editor editor,
                                                    Collection<String> hashedKeys) {
        CacheOrderUpdate update = boundedGoogleCacheOrder(
                prefs.getString(PREFS_GOOGLE_CACHE_ORDER_KEY, ""),
                hashedKeys,
                GOOGLE_CACHE_MAX_ENTRIES);
        for (String evicted : update.evictedKeys) editor.remove(evicted);
        editor.putString(PREFS_GOOGLE_CACHE_ORDER_KEY, update.nextOrder);
    }

    static CacheOrderUpdate boundedGoogleCacheOrder(String rawOrder, String hashedKey, int maxEntries) {
        return boundedGoogleCacheOrder(rawOrder, java.util.Collections.singletonList(hashedKey), maxEntries);
    }

    static CacheOrderUpdate boundedGoogleCacheOrder(String rawOrder, Collection<String> hashedKeys,
                                                    int maxEntries) {
        LinkedHashSet<String> order = new LinkedHashSet<>();
        if (!isBlank(rawOrder)) {
            String[] entries = rawOrder.split("\n");
            for (String entry : entries) {
                if (!isBlank(entry) && !PREFS_GOOGLE_CACHE_ORDER_KEY.equals(entry)) order.add(entry);
            }
        }
        if (hashedKeys != null) {
            for (String hashedKey : hashedKeys) {
                if (isBlank(hashedKey) || PREFS_GOOGLE_CACHE_ORDER_KEY.equals(hashedKey)) continue;
                order.remove(hashedKey);
                order.add(hashedKey);
            }
        }
        LinkedHashSet<String> evicted = new LinkedHashSet<>();
        while (order.size() > Math.max(0, maxEntries)) {
            String oldest = order.iterator().next();
            order.remove(oldest);
            evicted.add(oldest);
        }
        StringBuilder nextOrder = new StringBuilder();
        for (String entry : order) {
            if (nextOrder.length() > 0) nextOrder.append('\n');
            nextOrder.append(entry);
        }
        return new CacheOrderUpdate(nextOrder.toString(), evicted);
    }

    static final class CacheOrderUpdate {
        final String nextOrder;
        final LinkedHashSet<String> evictedKeys;

        CacheOrderUpdate(String nextOrder, LinkedHashSet<String> evictedKeys) {
            this.nextOrder = nextOrder;
            this.evictedKeys = evictedKeys;
        }
    }

    static ProcessedCacheOrderUpdate boundedProcessedCacheOrder(
            String rawOrder, String key, long bytes, long now, int maxEntries, long maxBytes, long maxAgeMs) {
        LinkedHashMap<String, ProcessedCacheEntry> order = new LinkedHashMap<>();
        LinkedHashSet<String> evicted = new LinkedHashSet<>();
        if (!isBlank(rawOrder)) {
            for (String row : rawOrder.split("\n")) {
                String[] parts = row.split("\\|", 3);
                if (parts.length != 3 || isBlank(parts[0])) continue;
                try {
                    long updated = Long.parseLong(parts[1]);
                    long size = Math.max(0L, Long.parseLong(parts[2]));
                    if (maxAgeMs > 0L && now - updated > maxAgeMs) evicted.add(parts[0]);
                    else order.put(parts[0], new ProcessedCacheEntry(updated, size));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        order.remove(key);
        order.put(key, new ProcessedCacheEntry(now, Math.max(0L, bytes)));
        long totalBytes = 0L;
        for (ProcessedCacheEntry entry : order.values()) totalBytes += entry.bytes;
        while (order.size() > Math.max(0, maxEntries) || totalBytes > Math.max(0L, maxBytes)) {
            String eldest = order.keySet().iterator().next();
            ProcessedCacheEntry removed = order.remove(eldest);
            totalBytes -= removed.bytes;
            evicted.add(eldest);
        }
        StringBuilder next = new StringBuilder();
        for (Map.Entry<String, ProcessedCacheEntry> entry : order.entrySet()) {
            if (next.length() > 0) next.append('\n');
            next.append(entry.getKey()).append('|').append(entry.getValue().updatedAt)
                    .append('|').append(entry.getValue().bytes);
        }
        String nextOrder = next.toString();
        return new ProcessedCacheOrderUpdate(nextOrder, evicted, !nextOrder.equals(safe(rawOrder)));
    }

    static final class ProcessedCacheOrderUpdate {
        final String nextOrder;
        final LinkedHashSet<String> evictedKeys;
        final boolean changed;

        ProcessedCacheOrderUpdate(String nextOrder, LinkedHashSet<String> evictedKeys, boolean changed) {
            this.nextOrder = nextOrder;
            this.evictedKeys = evictedKeys;
            this.changed = changed;
        }
    }

    private static final class ProcessedCacheEntry {
        final long updatedAt;
        final long bytes;

        ProcessedCacheEntry(long updatedAt, long bytes) {
            this.updatedAt = updatedAt;
            this.bytes = bytes;
        }
    }

    private static String removeProcessedOrderEntry(String rawOrder, String key) {
        StringBuilder out = new StringBuilder();
        if (!isBlank(rawOrder)) {
            for (String row : rawOrder.split("\n")) {
                if (row.startsWith(key + "|")) continue;
                if (out.length() > 0) out.append('\n');
                out.append(row);
            }
        }
        return out.toString();
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(safe(value).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format(Locale.ROOT, "%02x", b));
            return hex.toString();
        } catch (Throwable t) {
            Diagnostics.warn("LyricCaches", "sha256", t);
            return String.valueOf(safe(value).hashCode());
        }
    }

}
