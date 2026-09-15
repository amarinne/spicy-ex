package com.eza.spicyex.beautifullyrics.entities;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Map;

public final class LyricsResponseCache {
    private static final String PREFS_CACHE = "SpotifyPlusLyricsResponseCache";
    private static final String UPDATED_SUFFIX = ":updated";

    private LyricsResponseCache() {
    }

    // LRCLIB's raw search response was never cached at all before this - every replay of a track
    // that falls back to LRCLIB (most tracks: "Spicy"/Apple Music is disabled by default and
    // Spotify's own native lyrics often lack sync, see LyricsSourcePreferences) re-hit the network
    // even for the exact same song moments later. Same store/quota/eviction as the Apple Music
    // raw cache above, just prefixed so the two sources' entries for the same trackId don't
    // clobber each other.
    private static final String LRCLIB_PREFIX = "lrclib:";

    public static String getLrclib(Context context, String trackId) {
        return get(context, LRCLIB_PREFIX + safeId(trackId));
    }

    public static void putLrclib(Context context, String trackId, String response) {
        put(context, LRCLIB_PREFIX + safeId(trackId), response);
    }

    private static String safeId(String trackId) {
        return trackId == null ? "" : trackId;
    }

    public static synchronized String get(Context context, String trackId) {
        if (context == null) return null;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_CACHE, Context.MODE_PRIVATE);
        String cacheKey = key(trackId);
        String response = prefs.getString(cacheKey, null);
        if (response == null) return null;

        // No age expiry: an unchanged compatible response stays reusable until explicit clear. The :updated stamp is only backfilled for order metadata.
        String updatedKey = cacheKey + UPDATED_SUFFIX;
        if (prefs.getLong(updatedKey, 0L) <= 0L) {
            prefs.edit().putLong(updatedKey, System.currentTimeMillis()).apply();
        }
        return response;
    }

    public static synchronized void put(Context context, String trackId, String response) {
        if (context == null || response == null || response.trim().isEmpty()) return;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_CACHE, Context.MODE_PRIVATE);
        String cacheKey = key(trackId);
        long now = System.currentTimeMillis();
        // Byte quota from the shared "Cache size" budget; entry count and age are passed
        // non-binding, so a store below quota never evicts on count or age alone.
        long totalBudget = com.eza.spicyex.lyrics.CacheStoragePolicy.totalBudget(context);
        RawLyricsCachePolicy.Decision decision = RawLyricsCachePolicy.planWrite(
                prefs.getAll(),
                cacheKey,
                response,
                now,
                Long.MAX_VALUE,
                Integer.MAX_VALUE,
                com.eza.spicyex.lyrics.CacheStoragePolicy.rawResponseQuota(totalBudget)
        );

        // Capacity may refuse a new write, but must never delete saved lyrics.
        SharedPreferences.Editor editor = prefs.edit();
        for (String removedKey : decision.removedPayloadKeys) editor.remove(removedKey).remove(removedKey + UPDATED_SUFFIX);
        for (String legacyKey : decision.legacyPayloadKeys) {
            editor.putLong(legacyKey + UPDATED_SUFFIX, now);
        }
        if (decision.retainWrite) editor.putString(cacheKey, response).putLong(cacheKey + UPDATED_SUFFIX, now);
        editor.apply();
    }

    public static synchronized void clear(Context context) {
        context.getSharedPreferences(PREFS_CACHE, Context.MODE_PRIVATE).edit().clear().apply();
    }

    /** Drops only one track's raw response (and its order stamp), keeping every other song. */
    public static synchronized void remove(Context context, String trackId) {
        if (context == null || trackId == null || trackId.isEmpty()) return;
        String cacheKey = key(trackId);
        context.getSharedPreferences(PREFS_CACHE, Context.MODE_PRIVATE).edit()
                .remove(cacheKey).remove(cacheKey + UPDATED_SUFFIX).apply();
    }

    /** Combined logical-payload usage of the raw response store, for the settings panel. */
    public static synchronized long usageBytes(Context context) {
        if (context == null) return 0L;
        long total = 0L;
        try {
            for (Map.Entry<String, ?> entry
                    : context.getSharedPreferences(PREFS_CACHE, Context.MODE_PRIVATE)
                    .getAll().entrySet()) {
                if (entry.getKey() != null && entry.getKey().endsWith(UPDATED_SUFFIX)) continue;
                if (entry.getValue() instanceof String) {
                    total += ((String) entry.getValue())
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                }
            }
        } catch (Throwable ignored) {
            // Unreadable store contributes only what was readable.
        }
        return total;
    }

    public static synchronized int entryCount(Context context) {
        if (context == null) return 0;
        int count = 0;
        for (Map.Entry<String, ?> entry : context.getSharedPreferences(PREFS_CACHE, Context.MODE_PRIVATE).getAll().entrySet()) {
            if (entry.getKey() != null && entry.getKey().endsWith(UPDATED_SUFFIX)) continue;
            if (entry.getValue() instanceof String) count++;
        }
        return count;
    }

    private static String key(String trackId) {
        return trackId == null ? "" : trackId;
    }
}
