package com.eza.spicyex.beautifullyrics.entities;

import android.content.Context;
import android.content.SharedPreferences;

public final class LyricsResponseCache {
    private static final String PREFS_CACHE = "SpotifyPlusLyricsResponseCache";
    private static final long MAX_AGE_MS = 3L * 24L * 60L * 60L * 1000L;
    private static final int MAX_ENTRIES = 32;
    private static final long MAX_PAYLOAD_BYTES = 2L * 1024L * 1024L;

    private LyricsResponseCache() {
    }

    public static synchronized String get(Context context, String trackId) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_CACHE, Context.MODE_PRIVATE);
        String cacheKey = key(trackId);
        String response = prefs.getString(cacheKey, null);
        if (response == null) return null;

        String updatedKey = cacheKey + ":updated";
        long updatedAt = prefs.getLong(updatedKey, 0L);
        long now = System.currentTimeMillis();
        if (updatedAt <= 0L) {
            prefs.edit().putLong(updatedKey, now).apply();
            return response;
        }
        if (now - updatedAt > MAX_AGE_MS) {
            prefs.edit().remove(cacheKey).remove(updatedKey).apply();
            return null;
        }
        return response;
    }

    public static synchronized void put(Context context, String trackId, String response) {
        if (response == null || response.trim().isEmpty()) return;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_CACHE, Context.MODE_PRIVATE);
        String cacheKey = key(trackId);
        long now = System.currentTimeMillis();
        RawLyricsCachePolicy.Decision decision = RawLyricsCachePolicy.planWrite(
                prefs.getAll(),
                cacheKey,
                response,
                now,
                MAX_AGE_MS,
                MAX_ENTRIES,
                MAX_PAYLOAD_BYTES
        );

        SharedPreferences.Editor editor = prefs.edit();
        for (String removedKey : decision.removedPayloadKeys) {
            editor.remove(removedKey).remove(removedKey + ":updated");
        }
        for (String legacyKey : decision.legacyPayloadKeys) {
            editor.putLong(legacyKey + ":updated", now);
        }
        if (decision.retainWrite) {
            editor.putString(cacheKey, response).putLong(cacheKey + ":updated", now);
        } else {
            editor.remove(cacheKey).remove(cacheKey + ":updated");
        }
        editor.apply();
    }

    public static synchronized void clear(Context context) {
        context.getSharedPreferences(PREFS_CACHE, Context.MODE_PRIVATE).edit().clear().apply();
    }

    private static String key(String trackId) {
        return trackId == null ? "" : trackId;
    }
}
