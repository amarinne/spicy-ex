package com.eza.spicyex.lyrics;

import android.content.Context;
import android.content.SharedPreferences;

import com.eza.spicyex.Diagnostics;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashSet;
import java.util.Locale;
import static com.eza.spicyex.lyrics.LyricUtils.isBlank;
import static com.eza.spicyex.lyrics.LyricUtils.safe;

/** Preference-backed caches used by native Spicy lyrics processing. */
public final class LyricCaches {
    private static final String PREFS_GOOGLE_CACHE = "SpotifyPlusNativeSpicyGoogleCache";
    private static final String PREFS_GOOGLE_CACHE_ORDER_KEY = "__cache_order";
    private static final String PREFS_PROCESSED_CACHE = "SpotifyPlusNativeSpicyProcessedCache";
    private static final int GOOGLE_CACHE_MAX_ENTRIES = 5000;
    private static final Object GOOGLE_CACHE_LOCK = new Object();

    private LyricCaches() {
    }

    public static void clearGoogle(Context context) {
        if (context == null) return;
        context.getSharedPreferences(PREFS_GOOGLE_CACHE, Context.MODE_PRIVATE).edit().clear().apply();
    }

    public static void clearProcessed(Context context) {
        if (context == null) return;
        context.getSharedPreferences(PREFS_PROCESSED_CACHE, Context.MODE_PRIVATE).edit().clear().apply();
    }

    public static String sourceLanguageForCache(String sourceLang) {
        return isBlank(sourceLang) || "unknown".equalsIgnoreCase(sourceLang) ? "auto" : sourceLang;
    }

    public static String romanizationKey(String trackId, String sourceLang, String text) {
        return "romanize|" + safe(trackId) + "|" + sourceLanguageForCache(sourceLang) + "|" + safe(text);
    }

    public static String translationKey(String trackId, String sourceLang, String targetLang, String text) {
        return "translate|" + safe(trackId) + "|" + sourceLanguageForCache(sourceLang) + "|" + safe(targetLang) + "|" + safe(text);
    }

    public static String processedDocumentKey(int processingVersion, String trackId, String language, RomanizationOptions opts) {
        return "processed-doc-v" + processingVersion
                + "|" + safe(trackId)
                + "|" + sourceLanguageForCache(language)
                + "|" + opts.cacheKey();
    }

    public static String getProcessedDocument(Context context, String key) {
        try {
            return context.getSharedPreferences(PREFS_PROCESSED_CACHE, Context.MODE_PRIVATE).getString(sha256(key), null);
        } catch (Throwable t) {
            Diagnostics.warn("LyricCaches", "getProcessedDocument", t);
            return null;
        }
    }

    public static void putProcessedDocument(Context context, String key, String value) {
        if (context == null || isBlank(value)) return;
        try {
            context.getSharedPreferences(PREFS_PROCESSED_CACHE, Context.MODE_PRIVATE).edit().putString(sha256(key), value).apply();
        } catch (Throwable t) {
            Diagnostics.warn("LyricCaches", "putProcessedDocument", t);
        }
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
        putGoogleValue(context, processingCacheKey(processingVersion, key), value);
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
        if (context == null || isBlank(value)) return;
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_GOOGLE_CACHE, Context.MODE_PRIVATE);
            String hashedKey = sha256(key);
            synchronized (GOOGLE_CACHE_LOCK) {
                SharedPreferences.Editor editor = prefs.edit().putString(hashedKey, value);
                recordBoundedGoogleCachePut(prefs, editor, hashedKey);
                editor.apply();
            }
        } catch (Throwable t) {
            Diagnostics.warn("LyricCaches", "putGoogleValue", t);
        }
    }

    private static void recordBoundedGoogleCachePut(SharedPreferences prefs, SharedPreferences.Editor editor, String hashedKey) {
        CacheOrderUpdate update = boundedGoogleCacheOrder(
                prefs.getString(PREFS_GOOGLE_CACHE_ORDER_KEY, ""),
                hashedKey,
                GOOGLE_CACHE_MAX_ENTRIES);
        for (String evicted : update.evictedKeys) editor.remove(evicted);
        editor.putString(PREFS_GOOGLE_CACHE_ORDER_KEY, update.nextOrder);
    }

    static CacheOrderUpdate boundedGoogleCacheOrder(String rawOrder, String hashedKey, int maxEntries) {
        LinkedHashSet<String> order = new LinkedHashSet<>();
        if (!isBlank(rawOrder)) {
            String[] entries = rawOrder.split("\n");
            for (String entry : entries) {
                if (!isBlank(entry) && !PREFS_GOOGLE_CACHE_ORDER_KEY.equals(entry)) order.add(entry);
            }
        }
        order.remove(hashedKey);
        order.add(hashedKey);
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
