package com.eza.spicyex.lyrics.session;

import android.content.Context;
import android.content.SharedPreferences;

import com.eza.spicyex.Diagnostics;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Durable store for accepted paid AI artifacts.
 *
 * <p>This store differs from every other cache in the pipeline in the one way that matters: it
 * never evicts. A Sound or Meaning artifact can be recomputed for some CPU or a free provider call,
 * so bounding those by recency and age is fine. A paid artifact cost the owner money, so dropping
 * one to make room for another is not a cache policy. When the store cannot fit a write it
 * <b>rejects and reports</b> it; nothing already accepted is removed.
 *
 * <p>There is also no TTL. Removal happens only on explicit request — {@link #remove} for one
 * artifact, {@link #clear} for the store. Build stamps and deploy epochs must call neither; see the
 * note in {@code DeployCacheCleaner}.
 *
 * <p>Writes are synchronous and report their real outcome. A caller that gets a non-durable
 * {@link Write} may keep displaying the result, but must not claim it was persisted.
 */
public final class AIPaidArtifactCache {
    private static final String PREFS = "SpotifyPlusAIPaidArtifactCache";
    private static final String INDEX_KEY = "__paid_index";
    private static final int SCHEMA_VERSION = 1;
    /**
     * A ceiling that exists to bound storage, not to make room. Reaching it fails the new write
     * rather than discarding an artifact somebody paid for, so it is set well above what a heavy
     * user reaches and is expected to prompt an explicit clear rather than silent turnover.
     */
    private static final int MAX_ENTRIES = 512;
    private static final long MAX_BYTES = 24L * 1024L * 1024L;
    private static final Object LOCK = new Object();

    private AIPaidArtifactCache() {
    }

    /** Outcome of a paid write. {@code reason} is an identity-safe token, never lyric text. */
    public static final class Write {
        public static final Write OK = new Write(true, "");

        public final boolean durable;
        public final String reason;

        private Write(boolean durable, String reason) {
            this.durable = durable;
            this.reason = Digests.nz(reason);
        }

        static Write rejected(String reason) {
            return new Write(false, reason);
        }
    }

    /** How full the store is, so the owner can be told before a paid write starts failing. */
    public static final class Stats {
        public final int entries;
        public final long bytes;
        public final int maxEntries;
        public final long maxBytes;

        Stats(int entries, long bytes, int maxEntries, long maxBytes) {
            this.entries = entries;
            this.bytes = bytes;
            this.maxEntries = maxEntries;
            this.maxBytes = maxBytes;
        }

        public boolean isFull() {
            return entries >= maxEntries || bytes >= maxBytes;
        }
    }

    /**
     * Stores {@code payload} under {@code identity}, replacing only an artifact of that exact
     * identity.
     *
     * @return {@link Write#durable} true only when the record actually reached storage
     */
    public static Write put(Context context, PaidArtifactIdentity identity, String payload) {
        if (context == null) return Write.rejected("no-context");
        if (identity == null || !identity.isComplete()) return Write.rejected("incomplete-identity");
        if (payload == null || payload.isEmpty()) return Write.rejected("empty-payload");
        try {
            JsonObject record = header(identity);
            record.addProperty("payload", payload);
            String value = record.toString();
            long bytes = value.getBytes(StandardCharsets.UTF_8).length;
            String key = entryKey(identity);
            synchronized (LOCK) {
                SharedPreferences prefs = prefs(context);
                Admission admission = admit(prefs.getString(INDEX_KEY, ""), key, bytes,
                        MAX_ENTRIES, MAX_BYTES);
                if (!admission.admitted) return Write.rejected(admission.reason);
                // commit, not apply: the caller is deciding whether to tell the owner their paid
                // result is safe, and apply() would let it say yes before the write happened.
                boolean committed = prefs.edit()
                        .putString(key, value)
                        .putString(INDEX_KEY, admission.nextIndex)
                        .commit();
                return committed ? Write.OK : Write.rejected("commit-failed");
            }
        } catch (Throwable t) {
            Diagnostics.warn("AIPaidArtifactCache", "put", t);
            return Write.rejected("write-failed");
        }
    }

    /** @return the stored payload, or null when nothing was stored for exactly this identity */
    public static String get(Context context, PaidArtifactIdentity identity) {
        if (context == null || identity == null || !identity.isComplete()) return null;
        try {
            String raw;
            synchronized (LOCK) {
                raw = prefs(context).getString(entryKey(identity), null);
            }
            if (raw == null || raw.isEmpty()) return null;
            JsonElement parsed = JsonParser.parseString(raw);
            if (!parsed.isJsonObject()) return null;
            JsonObject record = parsed.getAsJsonObject();
            if (!matches(record, identity)) return null;
            JsonElement payload = record.get("payload");
            if (payload == null || payload.isJsonNull()) return null;
            String value = payload.getAsString();
            return value == null || value.isEmpty() ? null : value;
        } catch (Throwable t) {
            Diagnostics.warn("AIPaidArtifactCache", "get", t);
            return null;
        }
    }

    /** Explicit single-artifact invalidation. Never called by a build epoch. */
    public static boolean remove(Context context, PaidArtifactIdentity identity) {
        if (context == null || identity == null || !identity.isComplete()) return false;
        try {
            String key = entryKey(identity);
            synchronized (LOCK) {
                SharedPreferences prefs = prefs(context);
                if (!prefs.contains(key)) return false;
                LinkedHashMap<String, Long> index = parseIndex(prefs.getString(INDEX_KEY, ""));
                index.remove(key);
                return prefs.edit().remove(key).putString(INDEX_KEY, render(index)).commit();
            }
        } catch (Throwable t) {
            Diagnostics.warn("AIPaidArtifactCache", "remove", t);
            return false;
        }
    }

    /** Explicit owner action only. A deploy epoch must never reach this. */
    public static void clear(Context context) {
        if (context == null) return;
        try {
            synchronized (LOCK) {
                prefs(context).edit().clear().commit();
            }
        } catch (Throwable t) {
            Diagnostics.warn("AIPaidArtifactCache", "clear", t);
        }
    }

    /** Explicit owner action: remove paid artifacts for one layer without touching the other. */
    public static void clearLayer(Context context, LayerKind kind) {
        if (context == null || kind == null) return;
        synchronized (LOCK) {
            try {
                SharedPreferences prefs = prefs(context);
                LinkedHashMap<String, Long> index = parseIndex(prefs.getString(INDEX_KEY, ""));
                SharedPreferences.Editor editor = prefs.edit();
                for (String key : new java.util.ArrayList<>(index.keySet())) {
                    String raw = prefs.getString(key, "");
                    try {
                        JsonObject record = JsonParser.parseString(raw).getAsJsonObject();
                        String layer = record.has("layer") ? record.get("layer").getAsString() : "";
                        if (kind.name().equals(layer)) {
                            editor.remove(key);
                            index.remove(key);
                        }
                    } catch (Throwable ignored) {
                    }
                }
                editor.putString(INDEX_KEY, render(index)).commit();
            } catch (Throwable t) {
                Diagnostics.warn("AIPaidArtifactCache", "clearLayer", t);
            }
        }
    }

    public static Stats stats(Context context) {
        if (context == null) return new Stats(0, 0L, MAX_ENTRIES, MAX_BYTES);
        try {
            LinkedHashMap<String, Long> index;
            synchronized (LOCK) {
                index = parseIndex(prefs(context).getString(INDEX_KEY, ""));
            }
            long bytes = 0L;
            for (Long size : index.values()) bytes += size;
            return new Stats(index.size(), bytes, MAX_ENTRIES, MAX_BYTES);
        } catch (Throwable t) {
            Diagnostics.warn("AIPaidArtifactCache", "stats", t);
            return new Stats(0, 0L, MAX_ENTRIES, MAX_BYTES);
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String entryKey(PaidArtifactIdentity identity) {
        return "paid-v" + SCHEMA_VERSION + "|" + Digests.sha256(identity.storageKey());
    }

    /** Exposed for tests: the header a stored record is validated against. */
    static JsonObject header(PaidArtifactIdentity identity) {
        JsonObject record = new JsonObject();
        record.addProperty("schema", SCHEMA_VERSION);
        record.addProperty("layer", identity.layerKind == null ? "" : identity.layerKind.name());
        record.addProperty("canonicalDigest", identity.canonicalDigest);
        record.addProperty("providerId", identity.providerId);
        record.addProperty("modelId", identity.modelId);
        record.addProperty("promptContractId", identity.promptContractId);
        return record;
    }

    /**
     * True when a stored record was produced for exactly this identity. The key is hashed, so this
     * is what stops a hash collision or a schema change from serving the wrong paid answer.
     */
    static boolean matches(JsonObject record, PaidArtifactIdentity identity) {
        if (record == null || identity == null) return false;
        JsonObject expected = header(identity);
        for (Map.Entry<String, JsonElement> field : expected.entrySet()) {
            JsonElement actual = record.get(field.getKey());
            if (actual == null || !actual.equals(field.getValue())) return false;
        }
        return true;
    }

    /**
     * Decides whether a paid write fits.
     *
     * <p>Unlike every other bound in the pipeline this returns no eviction list. The only outcomes
     * are "it fits" and "it does not", because the alternative is throwing away something the owner
     * paid for. A rewrite of the same identity is measured against its own stored size, so
     * re-storing a slightly larger version of an artifact does not need room for both.
     */
    static Admission admit(String rawIndex, String key, long bytes, int maxEntries, long maxBytes) {
        LinkedHashMap<String, Long> index = parseIndex(rawIndex);
        long safeMaxBytes = Math.max(0L, maxBytes);
        int safeMaxEntries = Math.max(1, maxEntries);
        long incoming = Math.max(0L, bytes);
        long otherBytes = 0L;
        int otherEntries = 0;
        for (Map.Entry<String, Long> entry : index.entrySet()) {
            if (entry.getKey().equals(key)) continue;
            otherBytes += entry.getValue();
            otherEntries++;
        }
        if (incoming > safeMaxBytes) return Admission.rejected("artifact-larger-than-store");
        if (otherEntries + 1 > safeMaxEntries) return Admission.rejected("store-full-entries");
        if (otherBytes + incoming > safeMaxBytes) return Admission.rejected("store-full-bytes");
        index.remove(key);
        index.put(key, incoming);
        return new Admission(true, "", render(index));
    }

    private static LinkedHashMap<String, Long> parseIndex(String rawIndex) {
        LinkedHashMap<String, Long> index = new LinkedHashMap<>();
        if (rawIndex == null || rawIndex.isEmpty()) return index;
        for (String row : rawIndex.split("\n")) {
            int split = row.lastIndexOf('|');
            if (split <= 0) continue;
            try {
                index.put(row.substring(0, split), Math.max(0L, Long.parseLong(row.substring(split + 1))));
            } catch (NumberFormatException ignored) {
                // Drop an unreadable row rather than corrupting the bound. The artifact it named
                // stays on disk; stats under-report until it is rewritten.
            }
        }
        return index;
    }

    private static String render(Map<String, Long> index) {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, Long> entry : index.entrySet()) {
            if (out.length() > 0) out.append('\n');
            out.append(entry.getKey()).append('|').append(entry.getValue());
        }
        return out.toString();
    }

    static final class Admission {
        final boolean admitted;
        final String reason;
        /** Meaningful only when {@link #admitted}; a rejected write changes no index. */
        final String nextIndex;

        Admission(boolean admitted, String reason, String nextIndex) {
            this.admitted = admitted;
            this.reason = reason;
            this.nextIndex = nextIndex;
        }

        static Admission rejected(String reason) {
            return new Admission(false, reason, "");
        }
    }
}
