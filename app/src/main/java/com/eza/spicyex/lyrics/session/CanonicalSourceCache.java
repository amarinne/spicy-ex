package com.eza.spicyex.lyrics.session;

import android.content.Context;
import android.content.SharedPreferences;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;

import com.eza.spicyex.Diagnostics;
import com.eza.spicyex.lyrics.LyricsDocument;

/**
 * Durable store for canonical lyric source, separate from every derived-artifact store.
 *
 * <p>Canonical lyrics are normal display authority, so this cache has <b>no normal-use TTL</b>:
 * entries are evicted only by explicit clear or by the entry/byte bound. Build stamps and deploy
 * epochs invalidate derived implementation artifacts; they must not delete canonical source.
 */
public final class CanonicalSourceCache {
    private static final String PREFS = "SpotifyPlusCanonicalSourceCache";
    private static final String ORDER_KEY = "__cache_order";
    private static final int MAX_ENTRIES = 96;
    private static final long MAX_BYTES = 8L * 1024L * 1024L;
    private static final Object LOCK = new Object();

    private CanonicalSourceCache() {
    }

    public static CanonicalSourceCodec.Record load(Context context, String trackUri) {
        if (context == null || trackUri == null || trackUri.isEmpty()) return null;
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String raw;
            synchronized (LOCK) {
                raw = prefs.getString(entryKey(trackUri), null);
            }
            return CanonicalSourceCodec.decode(raw);
        } catch (Throwable t) {
            Diagnostics.warn("CanonicalSourceCache", "load", t);
            return null;
        }
    }

    /**
     * Persists the canonical projection of {@code document}.
     *
     * @return true when the record is durable; false means the caller may keep displaying it but
     *         must not report it as persisted
     */
    public static boolean save(Context context, String trackUri, LyricsDocument document,
                               int sourceRevision, String canonicalDigest) {
        if (context == null || trackUri == null || trackUri.isEmpty() || document == null
                || document.lines.isEmpty()) {
            return false;
        }
        try {
            String value = CanonicalSourceCodec.encode(document, sourceRevision, canonicalDigest,
                    System.currentTimeMillis());
            if (value.isEmpty()) return false;
            SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String key = entryKey(trackUri);
            long bytes = value.getBytes(StandardCharsets.UTF_8).length;
            synchronized (LOCK) {
                Bound bound = plan(prefs.getString(ORDER_KEY, ""), key, bytes, MAX_ENTRIES, MAX_BYTES);
                if (bound.rejectedWrite) return false;
                SharedPreferences.Editor editor = prefs.edit();
                for (String evicted : bound.evicted) editor.remove(evicted);
                editor.putString(key, value).putString(ORDER_KEY, bound.nextOrder).apply();
            }
            return true;
        } catch (Throwable t) {
            Diagnostics.warn("CanonicalSourceCache", "save", t);
            return false;
        }
    }

    public static void clear(Context context) {
        if (context == null) return;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply();
    }

    private static String entryKey(String trackUri) {
        return "canon-v" + CanonicalSourceCodec.SCHEMA_VERSION + "|" + Digests.sha256(trackUri);
    }

    /**
     * Least-recently-written eviction by entry count and total bytes. Age is deliberately not an
     * input: canonical source does not expire during normal use.
     */
    static Bound plan(String rawOrder, String key, long bytes, int maxEntries, long maxBytes) {
        LinkedHashMap<String, Long> order = new LinkedHashMap<>();
        if (rawOrder != null && !rawOrder.isEmpty()) {
            for (String row : rawOrder.split("\n")) {
                int split = row.lastIndexOf('|');
                if (split <= 0) continue;
                try {
                    order.put(row.substring(0, split), Long.parseLong(row.substring(split + 1)));
                } catch (NumberFormatException ignored) {
                    // Drop unreadable rows rather than corrupting the bound.
                }
            }
        }
        LinkedHashSet<String> evicted = new LinkedHashSet<>();
        if (bytes > Math.max(0L, maxBytes)) {
            order.remove(key);
            evicted.add(key);
            return new Bound(render(order), evicted, true);
        }
        order.remove(key);
        order.put(key, Math.max(0L, bytes));
        long total = 0L;
        for (Long size : order.values()) total += size;
        while (order.size() > Math.max(1, maxEntries) || total > Math.max(0L, maxBytes)) {
            String eldest = order.keySet().iterator().next();
            if (eldest.equals(key)) break;
            total -= order.remove(eldest);
            evicted.add(eldest);
        }
        return new Bound(render(order), evicted, false);
    }

    private static String render(Map<String, Long> order) {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, Long> entry : order.entrySet()) {
            if (out.length() > 0) out.append('\n');
            out.append(entry.getKey()).append('|').append(entry.getValue());
        }
        return out.toString();
    }

    static final class Bound {
        final String nextOrder;
        final LinkedHashSet<String> evicted;
        final boolean rejectedWrite;

        Bound(String nextOrder, LinkedHashSet<String> evicted, boolean rejectedWrite) {
            this.nextOrder = nextOrder;
            this.evicted = evicted;
            this.rejectedWrite = rejectedWrite;
        }
    }
}
