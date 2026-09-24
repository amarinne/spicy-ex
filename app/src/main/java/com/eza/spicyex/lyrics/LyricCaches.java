package com.eza.spicyex.lyrics;

import android.content.Context;

import com.eza.spicyex.Diagnostics;
import com.eza.spicyex.lyrics.session.Digests;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
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
    /** Language detection rows. Its own store and its own schema: a detector or gate change here
     * never discards readings or translations. */
    private static final String PREFS_DETECTION_CACHE = "SpotifyPlusDetectionArtifactCache";
    private static final String PREFS_PROCESSED_CACHE_ORDER_KEY = "__cache_order";
    private static final Object GOOGLE_CACHE_LOCK = new Object();
    private static final Object PROCESSED_CACHE_LOCK = new Object();

    private LyricCaches() {
    }
    /**
     * Byte quotas for the preference-backed stores, derived from the shared "Cache size" budget.
     * {@code Long.MAX_VALUE} (CacheStoragePolicy.UNLIMITED) means no byte or entry-count eviction.
     */
    public static long soundQuotaBytes(Context context) {
        return CacheStoragePolicy.soundQuota(CacheStoragePolicy.totalBudget(context));
    }

    public static long meaningQuotaBytes(Context context) {
        return CacheStoragePolicy.meaningQuota(CacheStoragePolicy.totalBudget(context));
    }

    public static long googleQuotaBytes(Context context) {
        return CacheStoragePolicy.googleQuota(CacheStoragePolicy.totalBudget(context));
    }

    public static long detectionQuotaBytes(Context context) {
        return CacheStoragePolicy.detectionQuota(CacheStoragePolicy.totalBudget(context));
    }

    public static int googleStoreEntryCount(Context context) {
        return preferenceStoreEntryCount(context, PREFS_GOOGLE_CACHE, PREFS_GOOGLE_CACHE_ORDER_KEY);
    }

    public static int soundStoreEntryCount(Context context) {
        return preferenceStoreEntryCount(context, PREFS_SOUND_CACHE, PREFS_PROCESSED_CACHE_ORDER_KEY);
    }

    public static int meaningStoreEntryCount(Context context) {
        return preferenceStoreEntryCount(context, PREFS_MEANING_CACHE, PREFS_PROCESSED_CACHE_ORDER_KEY);
    }

    public static int detectionStoreEntryCount(Context context) {
        return preferenceStoreEntryCount(context, PREFS_DETECTION_CACHE, PREFS_PROCESSED_CACHE_ORDER_KEY);
    }

    private static int preferenceStoreEntryCount(Context context, String prefsName, String orderKey) {
        return SpicyCacheStore.entryCount(context, prefsName);
    }

    public static void clearGoogle(Context context) {
        SpicyCacheStore.clear(context, PREFS_GOOGLE_CACHE);
    }

    public static void clearProcessed(Context context) {
        SpicyCacheStore.clear(context, PREFS_PROCESSED_CACHE);
        SpicyCacheStore.clear(context, PREFS_SOUND_CACHE);
        SpicyCacheStore.clear(context, PREFS_MEANING_CACHE);
        SpicyCacheStore.clear(context, PREFS_DETECTION_CACHE);
    }

    /** Drops detection rows only. A detector or gate change must not touch readings/translations. */
    public static void clearDetectionArtifacts(Context context) {
        SpicyCacheStore.clear(context, PREFS_DETECTION_CACHE);
    }

    /** Drops Sound artifacts only. A Sound contract change must not touch Meaning. */
    public static void clearSoundArtifacts(Context context) {
        SpicyCacheStore.clear(context, PREFS_SOUND_CACHE);
    }

    /** Drops Meaning artifacts only. A Meaning contract change must not touch Sound. */
    public static void clearMeaningArtifacts(Context context) {
        SpicyCacheStore.clear(context, PREFS_MEANING_CACHE);
    }

    public static String getSoundArtifact(Context context, String key) {
        return getBoundedRecord(context, PREFS_SOUND_CACHE, key);
    }

    public static boolean putSoundArtifact(Context context, String key, String value) {
        return putBoundedRecord(context, PREFS_SOUND_CACHE, key, value, soundQuotaBytes(context));
    }

    public static String getMeaningArtifact(Context context, String key) {
        return getBoundedRecord(context, PREFS_MEANING_CACHE, key);
    }

    public static boolean putMeaningArtifact(Context context, String key, String value) {
        return putBoundedRecord(context, PREFS_MEANING_CACHE, key, value, meaningQuotaBytes(context));
    }

    public static String getDetectionArtifact(Context context, String key) {
        return getBoundedRecord(context, PREFS_DETECTION_CACHE, key);
    }

    /** Detection records are compact but never unbounded; cap entries as well as bytes. */
    static final int DETECTION_MAX_ENTRIES = 2000;

    public static boolean putDetectionArtifact(Context context, String key, String value) {
        return putBoundedRecord(context, PREFS_DETECTION_CACHE, key, value,
                detectionQuotaBytes(context), DETECTION_MAX_ENTRIES);
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

    /** Reads retained artifacts even when the owner has reduced the storage budget. */
    private static String getBoundedRecord(Context context, String prefsName, String key) {
        if (context == null) return null;
        try {
            return SpicyCacheStore.get(context, prefsName, sha256(key));
        } catch (Throwable t) {
            Diagnostics.warn("LyricCaches", "getBoundedRecord", t);
            return null;
        }
    }

    /** Refuses writes that would evict saved artifacts; successful writes are durable. */
    private static boolean putBoundedRecord(Context context, String prefsName, String key, String value,
                                         long quotaBytes) {
        return putBoundedRecord(context, prefsName, key, value, quotaBytes, Integer.MAX_VALUE);
    }

    private static boolean putBoundedRecord(Context context, String prefsName, String key, String value,
                                         long quotaBytes, int maxEntries) {
        if (context == null || isBlank(value)) return false;
        try {
            return SpicyCacheStore.put(context, prefsName, sha256(key), value, quotaBytes);
        } catch (Throwable t) {
            Diagnostics.warn("LyricCaches", "putBoundedRecord", t);
            return false;
        }
    }

    /**
     * Sound artifact key: canonical digest plus Sound configuration only. No translation backend,
     * target language, or Meaning contract may appear here.
     *
     * <p>Keeping the configuration in the key means a track holds one record per reading style it
     * has been shown in, so returning to a style is instant rather than a re-derivation. The store
     * is sized for that; see {@link CacheStoragePolicy#soundQuota(long)}.
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

    /**
     * Detection artifact key: canonical digest plus detection schema version only. Detector policy
     * identity lives inside the record, so a policy change invalidates rows without stranding the
     * store under a key no reader can compute.
     */
    public static String detectionArtifactKey(String canonicalDigest, int detectionSchemaVersion) {
        return "detection/" + safe(canonicalDigest) + "/" + detectionSchemaVersion;
    }

    /**
     * Provider-translation detection key: the text itself. Provider translations are not canonical
     * rows, so their detection cannot hang off a canonical digest.
     */
    public static String providerDetectionKey(String text) {
        return "detection/text/" + sha256(safe(text));
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
            return SpicyCacheStore.get(context, PREFS_GOOGLE_CACHE, sha256(key));
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
            long quota = googleQuotaBytes(context);
            synchronized (GOOGLE_CACHE_LOCK) {
                for (Map.Entry<String, String> entry : values.entrySet()) {
                    if (entry == null || isBlank(entry.getValue())) continue;
                    SpicyCacheStore.put(context, PREFS_GOOGLE_CACHE,
                            sha256(entry.getKey()), entry.getValue(), quota);
                }
            }
        } catch (Throwable t) {
            Diagnostics.warn("LyricCaches", "putGoogleValue", t);
        }
    }

    /** Combined logical-payload usage of the Google processing store, for the settings panel. */
    public static long googleStoreUsageBytes(Context context) {
        return CacheStoragePolicy.preferenceStoreUsage(context, PREFS_GOOGLE_CACHE,
                PREFS_GOOGLE_CACHE_ORDER_KEY);
    }

    /** Combined logical-payload usage of the Sound artifact store, for the settings panel. */
    public static long soundStoreUsageBytes(Context context) {
        return CacheStoragePolicy.preferenceStoreUsage(context, PREFS_SOUND_CACHE,
                PREFS_PROCESSED_CACHE_ORDER_KEY);
    }

    /** Combined logical-payload usage of the Meaning artifact store, for the settings panel. */
    public static long meaningStoreUsageBytes(Context context) {
        return CacheStoragePolicy.preferenceStoreUsage(context, PREFS_MEANING_CACHE,
                PREFS_PROCESSED_CACHE_ORDER_KEY);
    }

    /** Combined logical-payload usage of the detection artifact store, for the settings panel. */
    public static long detectionStoreUsageBytes(Context context) {
        return CacheStoragePolicy.preferenceStoreUsage(context, PREFS_DETECTION_CACHE,
                PREFS_PROCESSED_CACHE_ORDER_KEY);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(safe(value).getBytes(StandardCharsets.UTF_8));
            return Digests.hex(hash);
        } catch (Throwable t) {
            Diagnostics.warn("LyricCaches", "sha256", t);
            return String.valueOf(safe(value).hashCode());
        }
    }

}
