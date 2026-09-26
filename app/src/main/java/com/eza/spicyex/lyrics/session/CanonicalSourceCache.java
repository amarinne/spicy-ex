package com.eza.spicyex.lyrics.session;

import android.content.Context;

import com.eza.spicyex.Diagnostics;

/**
 * The public release's one-winner canonical store, now read-only. The catalog imports a track's
 * record from here the first time it loads that track; nothing writes here any more. Deleting a
 * track's saved lyrics also removes its record so the import cannot bring it back.
 */
public final class CanonicalSourceCache {
    private static final String PREFS = "SpotifyPlusCanonicalSourceCache";

    private CanonicalSourceCache() {
    }

    public static CanonicalSourceCodec.Record load(Context context, String trackUri) {
        if (context == null || trackUri == null || trackUri.isEmpty()) return null;
        try {
            String raw = com.eza.spicyex.lyrics.SpicyCacheStore.get(context, PREFS, entryKey(trackUri));
            return CanonicalSourceCodec.decode(raw);
        } catch (Throwable t) {
            Diagnostics.warn("CanonicalSourceCache", "load", t);
            return null;
        }
    }

    public static void clear(Context context) {
        com.eza.spicyex.lyrics.SpicyCacheStore.clear(context, PREFS);
    }

    /** Drops only one track's legacy record. */
    public static void remove(Context context, String trackUri) {
        if (context == null || trackUri == null || trackUri.isEmpty()) return;
        com.eza.spicyex.lyrics.SpicyCacheStore.remove(context, PREFS, entryKey(trackUri));
    }

    /** Combined logical-payload usage of the legacy store, for the settings panel. */
    public static long usageBytes(Context context) {
        return com.eza.spicyex.lyrics.SpicyCacheStore.usageBytes(context, PREFS);
    }

    public static int entryCount(Context context) {
        return com.eza.spicyex.lyrics.SpicyCacheStore.entryCount(context, PREFS);
    }

    private static String entryKey(String trackUri) {
        return "canon-v" + CanonicalSourceCodec.SCHEMA_VERSION + "|" + Digests.sha256(trackUri);
    }
}
