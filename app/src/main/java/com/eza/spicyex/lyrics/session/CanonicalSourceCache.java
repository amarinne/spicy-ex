package com.eza.spicyex.lyrics.session;

import android.content.Context;

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
 * entries are deleted only by explicit clear. A full quota refuses writes instead of deleting
 * saved lyrics. Build stamps and deploy epochs never delete canonical source.
 */
public final class CanonicalSourceCache {
    private static final String PREFS = "SpotifyPlusCanonicalSourceCache";
    private static final String ORDER_KEY = "__cache_order";
    private static final Object LOCK = new Object();

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

    /** Loads only when the record was acquired under the requested selection identity. */
    public static CanonicalSourceCodec.Record load(Context context, String trackUri,
                                                   String selectionIdentity) {
        CanonicalSourceCodec.Record record = load(context, trackUri);
        if (record == null) return null;
        if (selectionIdentity == null || selectionIdentity.isEmpty()) return record;
        return LyricsSourcePreferences.cacheCompatible(record.selectionIdentity, selectionIdentity)
                ? record : null;
    }

    /**
     * Persists the canonical projection of {@code document}.
     *
     * @return true when the record is durable; false means the caller may keep displaying it but
     *         must not report it as persisted
     */
    public static boolean save(Context context, String trackUri, LyricsDocument document,
                               int sourceRevision, String canonicalDigest) {
        return save(context, trackUri, document, sourceRevision, canonicalDigest, "");
    }

    /** Saves canonical lyrics together with the source-selection identity that produced them. */
    public static boolean save(Context context, String trackUri, LyricsDocument document,
                               int sourceRevision, String canonicalDigest, String selectionIdentity) {
        return save(context, trackUri, document, sourceRevision, canonicalDigest, selectionIdentity,
                "", "");
    }

    /** As above, recording the song's title and artist for the cache browser. */
    public static boolean save(Context context, String trackUri, LyricsDocument document,
                               int sourceRevision, String canonicalDigest, String selectionIdentity,
                               String title, String artist) {
        if (context == null || trackUri == null || trackUri.isEmpty() || document == null
                || document.lines.isEmpty()) {
            return false;
        }
        try {
            String value = CanonicalSourceCodec.encode(document, sourceRevision, canonicalDigest,
                    System.currentTimeMillis(), selectionIdentity, title, artist, trackUri);
            if (value.isEmpty()) return false;
            // Byte quota from the shared "Cache size" budget. Eviction is least-recently-used
            // inside the store; there is no entry-count bound and no age expiry.
            return com.eza.spicyex.lyrics.SpicyCacheStore.put(context, PREFS, entryKey(trackUri), value,
                    com.eza.spicyex.lyrics.CacheStoragePolicy.canonicalQuota(
                            com.eza.spicyex.lyrics.CacheStoragePolicy.totalBudget(context)));
        } catch (Throwable t) {
            Diagnostics.warn("CanonicalSourceCache", "save", t);
            return false;
        }
    }

    public static void clear(Context context) {
        com.eza.spicyex.lyrics.SpicyCacheStore.clear(context, PREFS);
    }

    /** Drops only one track's canonical record, keeping every other cached song. */
    public static void remove(Context context, String trackUri) {
        if (context == null || trackUri == null || trackUri.isEmpty()) return;
        com.eza.spicyex.lyrics.SpicyCacheStore.remove(context, PREFS, entryKey(trackUri));
    }

    /** Combined logical-payload usage of the canonical source store, for the settings panel. */
    public static long usageBytes(Context context) {
        return com.eza.spicyex.lyrics.SpicyCacheStore.usageBytes(context, PREFS);
    }

    public static int entryCount(Context context) {
        return com.eza.spicyex.lyrics.SpicyCacheStore.entryCount(context, PREFS);
    }

    /** One cached song as the settings panel lists it. */
    public static final class Entry {
        public final String key;
        public final String trackUri;
        public final String trackId;
        /** Empty for songs saved before titles were recorded; {@link #firstLine} stands in. */
        public final String title;
        public final String artist;
        /** Where the lyrics came from, as the document named it ("LRCLIB", "Apple Music"...). */
        public final String source;
        public final String firstLine;
        public final long bytes;
        public final long savedAtMs;

        Entry(String key, String trackUri, String trackId, String title, String artist,
              String source, String firstLine, long bytes, long savedAtMs) {
            this.key = key;
            this.trackUri = trackUri;
            this.trackId = trackId;
            this.title = title;
            this.artist = artist;
            this.source = source;
            this.firstLine = firstLine;
            this.bytes = bytes;
            this.savedAtMs = savedAtMs;
        }
    }

    /** Every cached song, newest first. Reads the whole store: call off the main thread. */
    public static java.util.List<Entry> entries(Context context) {
        final java.util.List<Entry> out = new java.util.ArrayList<>();
        com.eza.spicyex.lyrics.SpicyCacheStore.scan(context, PREFS, (key, value, bytes, updatedAt) -> {
            Entry entry = summarize(key, value, bytes, updatedAt);
            if (entry != null) out.add(entry);
        });
        return out;
    }

    /**
     * Drops one listed song: its canonical record and the raw provider responses kept for the
     * same track, so a replay really fetches again.
     */
    public static void remove(Context context, Entry entry) {
        if (context == null || entry == null) return;
        com.eza.spicyex.lyrics.SpicyCacheStore.remove(context, PREFS, entry.key);
        String trackId = entry.trackId;
        if (trackId.isEmpty() && !entry.trackUri.isEmpty()) {
            trackId = com.eza.spicyex.lyrics.LyricUtils.trackIdFromUri(entry.trackUri);
        }
        if (trackId != null && !trackId.isEmpty()) {
            com.eza.spicyex.beautifullyrics.entities.LyricsResponseCache.remove(context, trackId);
            com.eza.spicyex.beautifullyrics.entities.LyricsResponseCache.remove(context, "lrclib:" + trackId);
        }
    }

    /**
     * The top-level fields and the first line's text, streamed: the rest of the lyrics (the bulk
     * of every record) is skipped rather than parsed.
     */
    static Entry summarize(String key, String raw, long bytes, long updatedAt) {
        if (raw == null || raw.isEmpty()) return null;
        String trackUri = "";
        String trackId = "";
        String title = "";
        String artist = "";
        String provider = "";
        String selected = "";
        String firstLine = "";
        long savedAt = updatedAt;
        try (com.google.gson.stream.JsonReader reader =
                     new com.google.gson.stream.JsonReader(new java.io.StringReader(raw))) {
            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();
                if (reader.peek() == com.google.gson.stream.JsonToken.NULL) {
                    reader.skipValue();
                    continue;
                }
                switch (name) {
                    case "trackUri": trackUri = reader.nextString(); break;
                    case "trackId": trackId = reader.nextString(); break;
                    case "title": title = reader.nextString(); break;
                    case "artist": artist = reader.nextString(); break;
                    case "provider": provider = reader.nextString(); break;
                    case "selectedSource": selected = reader.nextString(); break;
                    case "savedAtMs": savedAt = (long) reader.nextDouble(); break;
                    case "lines":
                        reader.beginArray();
                        while (reader.hasNext()) {
                            if (firstLine.isEmpty() && reader.peek() == com.google.gson.stream.JsonToken.BEGIN_OBJECT) {
                                reader.beginObject();
                                while (reader.hasNext()) {
                                    String field = reader.nextName();
                                    if ("text".equals(field) && reader.peek() == com.google.gson.stream.JsonToken.STRING) {
                                        firstLine = reader.nextString().trim();
                                    } else {
                                        reader.skipValue();
                                    }
                                }
                                reader.endObject();
                            } else {
                                reader.skipValue();
                            }
                        }
                        reader.endArray();
                        break;
                    default:
                        reader.skipValue();
                }
            }
        } catch (Throwable t) {
            return null;
        }
        String source = selected.isEmpty() ? provider : selected;
        return new Entry(key, trackUri, trackId, title, artist, source, firstLine, bytes, savedAt);
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
        boolean canRetainWrite() {
            return !rejectedWrite && evicted.isEmpty();
        }

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
