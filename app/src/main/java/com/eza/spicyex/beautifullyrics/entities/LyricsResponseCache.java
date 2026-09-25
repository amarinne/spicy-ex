package com.eza.spicyex.beautifullyrics.entities;

import android.content.Context;

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
        // No age expiry: an unchanged compatible response stays reusable until an explicit clear.
        // The ":updated" stamps the preferences version kept beside every payload are gone - the
        // store's own updated_at_ms column is what eviction orders by now.
        return com.eza.spicyex.lyrics.SpicyCacheStore.get(context, PREFS_CACHE, key(trackId));
    }

    public static synchronized void put(Context context, String trackId, String response) {
        if (context == null || response == null || response.trim().isEmpty()) return;
        // Byte quota from the shared "Cache size" budget. Capacity may refuse a new write, but
        // never deletes a saved response to make room for one that does not fit.
        long totalBudget = com.eza.spicyex.lyrics.CacheStoragePolicy.totalBudget(context);
        com.eza.spicyex.lyrics.SpicyCacheStore.put(context, PREFS_CACHE, key(trackId), response,
                com.eza.spicyex.lyrics.CacheStoragePolicy.rawResponseQuota(totalBudget));
    }

    public static synchronized void clear(Context context) {
        com.eza.spicyex.lyrics.SpicyCacheStore.clear(context, PREFS_CACHE);
    }

    /** Drops both raws one track stored (canonical and LRCLIB), keeping every other song. */
    public static synchronized void remove(Context context, String trackId) {
        if (context == null || trackId == null || trackId.isEmpty()) return;
        com.eza.spicyex.lyrics.SpicyCacheStore.remove(context, PREFS_CACHE, key(trackId));
        com.eza.spicyex.lyrics.SpicyCacheStore.remove(context, PREFS_CACHE,
                LRCLIB_PREFIX + key(trackId));
    }

    /** Combined logical-payload usage of the raw response store, for the settings panel. */
    public static synchronized long usageBytes(Context context) {
        return com.eza.spicyex.lyrics.SpicyCacheStore.usageBytes(context, PREFS_CACHE);
    }

    public static synchronized int entryCount(Context context) {
        return com.eza.spicyex.lyrics.SpicyCacheStore.entryCount(context, PREFS_CACHE);
    }

    private static String key(String trackId) {
        return trackId == null ? "" : trackId;
    }
}
