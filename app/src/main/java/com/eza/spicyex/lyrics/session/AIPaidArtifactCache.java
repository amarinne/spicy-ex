package com.eza.spicyex.lyrics.session;

import android.content.Context;
import android.content.SharedPreferences;

import com.eza.spicyex.Diagnostics;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Durable SQLite store for paid AI work inside Spotify's private app storage.
 *
 * <p>The injected code has the same Spotify {@link Context} under rooted LSPosed and rootless
 * LSPatch, so this store needs no module-process IPC. Paid entries never expire and are never
 * evicted. Capacity is one logical byte budget; no entry-count limit can strand unused space.
 *
 * <p>Spotify main-process startup after B515 imports every paid-v1 SharedPreferences value in one
 * transaction. The old XML stays untouched as transition evidence, but all new writes go only to
 * SQLite.
 */
public final class AIPaidArtifactCache {
    static final String LEGACY_PREFS = "SpotifyPlusAIPaidArtifactCache";
    static final String LEGACY_INDEX_KEY = "__paid_index";
    static final int SCHEMA_VERSION = 1;
    static final Object LOCK = new Object();

    /**
     * Paid-AI byte quota from the shared "Cache size" budget (50% plus rounding remainder).
     * Existing records are never evicted to make room; a full finite quota still refuses writes.
     */
    private static long quotaBytes(Context context) {
        return com.eza.spicyex.lyrics.CacheStoragePolicy.paidAiQuota(
                com.eza.spicyex.lyrics.CacheStoragePolicy.totalBudget(context));
    }

    private static volatile AIPaidArtifactDatabase database;

    private AIPaidArtifactCache() {
    }

    /** Runs the one-time B515 transition without reading a record or starting provider work. */
    public static boolean prepare(Context context) {
        if (context == null) return false;
        try {
            synchronized (LOCK) {
                return database(context).ensureV515Migration();
            }
        } catch (Throwable failure) {
            Diagnostics.warn("AIPaidArtifactCache", "prepare", failure);
            return false;
        }
    }

    /** Outcome of a paid write. {@code reason} is identity-safe and never contains lyric text. */
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

    /** One identity's pre-provider capacity claim. */
    public static final class Reservation {
        public enum Status { ADMITTED, FULL, BUSY, UNAVAILABLE }

        public final Status status;
        public final String reason;
        final String token;
        final String entryKey;

        private Reservation(Status status, String reason, String token, String entryKey) {
            this.status = status;
            this.reason = Digests.nz(reason);
            this.token = Digests.nz(token);
            this.entryKey = Digests.nz(entryKey);
        }

        public boolean accepted() {
            return status == Status.ADMITTED;
        }

        static Reservation admitted(String token, String entryKey) {
            return new Reservation(Status.ADMITTED, "", token, entryKey);
        }

        static Reservation rejected(Status status, String reason) {
            return new Reservation(status, reason, "", "");
        }
    }

    /** How full the store is. {@code maxEntries} remains only for source compatibility. */
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
            return bytes >= maxBytes;
        }
    }

    /** Stores a paid payload against the reservation held by its run. */
    public static Write put(Context context, PaidArtifactIdentity identity, String payload,
                            Reservation reservation) {
        if (context == null) return Write.rejected("no-context");
        if (identity == null || !identity.isComplete()) return Write.rejected("incomplete-identity");
        if (payload == null || payload.isEmpty()) return Write.rejected("empty-payload");
        try {
            JsonObject record = header(identity);
            record.addProperty("payload", payload);
            byte[] value = record.toString().getBytes(StandardCharsets.UTF_8);
            synchronized (LOCK) {
                AIPaidArtifactDatabase db = database(context);
                if (!db.ensureV515Migration()) return Write.rejected("migration-failed");
                return db.put(entryKey(identity), identity.layerKind.name(), value, reservation,
                        quotaBytes(context));
            }
        } catch (Throwable failure) {
            Diagnostics.warn("AIPaidArtifactCache", "put", failure);
            return Write.rejected("write-failed");
        }
    }

    /** Reserves a conservative final-record size before any provider dispatch. */
    public static Reservation reserve(Context context, PaidArtifactIdentity identity,
                                      long maxRecordBytes, Reservation current) {
        if (context == null || identity == null || !identity.isComplete()) {
            return Reservation.rejected(Reservation.Status.UNAVAILABLE, "storage-unavailable");
        }
        try {
            synchronized (LOCK) {
                AIPaidArtifactDatabase db = database(context);
                if (!db.ensureV515Migration()) {
                    return Reservation.rejected(Reservation.Status.UNAVAILABLE,
                            "migration-failed");
                }
                return db.reserve(entryKey(identity), identity.layerKind.name(), maxRecordBytes,
                        current, quotaBytes(context));
            }
        } catch (Throwable failure) {
            Diagnostics.warn("AIPaidArtifactCache", "reserve", failure);
            return Reservation.rejected(Reservation.Status.UNAVAILABLE, "storage-unavailable");
        }
    }

    public static void release(Context context, Reservation reservation) {
        if (context == null || reservation == null || !reservation.accepted()) return;
        synchronized (LOCK) {
            database(context).release(reservation);
        }
    }

    /** @return stored payload for this exact identity, or null when none is trustworthy */
    public static String get(Context context, PaidArtifactIdentity identity) {
        if (context == null || identity == null || !identity.isComplete()) return null;
        String key = entryKey(identity);
        try {
            byte[] raw;
            synchronized (LOCK) {
                AIPaidArtifactDatabase db = database(context);
                if (!db.ensureV515Migration()) return legacyPayload(context, identity, key);
                raw = db.get(key);
            }
            return payloadOf(raw, identity);
        } catch (Throwable failure) {
            Diagnostics.warn("AIPaidArtifactCache", "get", failure);
            return legacyPayload(context, identity, key);
        }
    }

    /** Explicit single-record removal. Build epochs never call this. */
    public static boolean remove(Context context, PaidArtifactIdentity identity) {
        if (context == null || identity == null || !identity.isComplete()) return false;
        try {
            synchronized (LOCK) {
                AIPaidArtifactDatabase db = database(context);
                return db.ensureV515Migration() && db.remove(entryKey(identity));
            }
        } catch (Throwable failure) {
            Diagnostics.warn("AIPaidArtifactCache", "remove", failure);
            return false;
        }
    }

    /** Explicit owner action only. The v515 XML remains untouched for transition safety. */
    public static void clear(Context context) {
        if (context == null) return;
        try {
            synchronized (LOCK) {
                AIPaidArtifactDatabase db = database(context);
                if (db.ensureV515Migration()) db.clear();
            }
        } catch (Throwable failure) {
            Diagnostics.warn("AIPaidArtifactCache", "clear", failure);
        }
    }

    /** Explicit owner action: removes one paid layer without touching the other. */
    public static void clearLayer(Context context, LayerKind kind) {
        if (context == null || kind == null) return;
        try {
            synchronized (LOCK) {
                AIPaidArtifactDatabase db = database(context);
                if (db.ensureV515Migration()) db.clearLayer(kind.name());
            }
        } catch (Throwable failure) {
            Diagnostics.warn("AIPaidArtifactCache", "clearLayer", failure);
        }
    }

    public static Stats stats(Context context) {
        long quota = context == null
                ? quotaBytes(null) : quotaBytes(context);
        if (context == null) return new Stats(0, 0L, Integer.MAX_VALUE, quota);
        try {
            synchronized (LOCK) {
                AIPaidArtifactDatabase db = database(context);
                if (db.ensureV515Migration()) return db.stats(quota);
            }
        } catch (Throwable failure) {
            Diagnostics.warn("AIPaidArtifactCache", "stats", failure);
        }
        List<MigrationRow> legacy = migrationRows(context.getSharedPreferences(
                LEGACY_PREFS, Context.MODE_PRIVATE).getAll());
        long bytes = 0L;
        for (MigrationRow row : legacy) bytes = safeAdd(bytes, row.valueBytes.length);
        return new Stats(legacy.size(), bytes, Integer.MAX_VALUE, quota);
    }

    /**
     * Combined logical-payload usage of the paid artifact store, for the settings panel.
     * Counts SQLite {@code raw_bytes} only; the legacy migration XML is never counted here, and an
     * unreadable store contributes zero.
     */
    public static long usageBytes(Context context) {
        if (context == null) return 0L;
        try {
            synchronized (LOCK) {
                AIPaidArtifactDatabase db = database(context);
                if (db.ensureV515Migration()) return db.usageBytes();
            }
        } catch (Throwable failure) {
            Diagnostics.warn("AIPaidArtifactCache", "usageBytes", failure);
        }
        return 0L;
    }

    private static AIPaidArtifactDatabase database(Context context) {
        AIPaidArtifactDatabase result = database;
        if (result != null) return result;
        synchronized (LOCK) {
            if (database == null) database = new AIPaidArtifactDatabase(context);
            return database;
        }
    }

    private static String entryKey(PaidArtifactIdentity identity) {
        return "paid-v" + SCHEMA_VERSION + "|" + Digests.sha256(identity.storageKey());
    }

    /** Exposed for tests: wrapper header validated before a payload is served. */
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

    static boolean matches(JsonObject record, PaidArtifactIdentity identity) {
        if (record == null || identity == null) return false;
        JsonObject expected = header(identity);
        for (Map.Entry<String, JsonElement> field : expected.entrySet()) {
            JsonElement actual = record.get(field.getKey());
            if (actual == null || !actual.equals(field.getValue())) return false;
        }
        return true;
    }

    static String admissionReason(long otherBytes, long targetBytes, long maxBytes) {
        long safeMax = Math.max(0L, maxBytes);
        long target = Math.max(0L, targetBytes);
        if (target > safeMax) return "artifact-larger-than-store";
        return safeAdd(Math.max(0L, otherBytes), target) > safeMax
                ? "store-full-bytes" : "";
    }

    static long safeAdd(long left, long right) {
        long a = Math.max(0L, left);
        long b = Math.max(0L, right);
        return Long.MAX_VALUE - a < b ? Long.MAX_VALUE : a + b;
    }

    /** Pure v515 migration projection. Raw values are preserved byte-for-byte. */
    static List<MigrationRow> migrationRows(Map<String, ?> values) {
        if (values == null || values.isEmpty()) return Collections.emptyList();
        List<MigrationRow> rows = new ArrayList<>();
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            String key = Digests.nz(entry.getKey());
            if (LEGACY_INDEX_KEY.equals(key) || !key.startsWith("paid-v1|")) continue;
            if (!(entry.getValue() instanceof String)) continue;
            String value = (String) entry.getValue();
            rows.add(new MigrationRow(key, layerOf(value),
                    value.getBytes(StandardCharsets.UTF_8)));
        }
        rows.sort(Comparator.comparing(row -> row.key));
        return rows;
    }

    private static String layerOf(String raw) {
        try {
            JsonObject record = JsonParser.parseString(raw).getAsJsonObject();
            return record.has("layer") ? Digests.nz(record.get("layer").getAsString()) : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String payloadOf(byte[] raw, PaidArtifactIdentity identity) {
        if (raw == null || raw.length == 0) return null;
        try {
            JsonObject record = JsonParser.parseString(
                    new String(raw, StandardCharsets.UTF_8)).getAsJsonObject();
            if (!matches(record, identity)) return null;
            JsonElement payload = record.get("payload");
            if (payload == null || payload.isJsonNull()) return null;
            String value = payload.getAsString();
            return value == null || value.isEmpty() ? null : value;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String legacyPayload(Context context, PaidArtifactIdentity identity, String key) {
        try {
            SharedPreferences preferences = context.getSharedPreferences(
                    LEGACY_PREFS, Context.MODE_PRIVATE);
            String raw = preferences.getString(key, null);
            return raw == null ? null : payloadOf(raw.getBytes(StandardCharsets.UTF_8), identity);
        } catch (Throwable ignored) {
            return null;
        }
    }

    static final class MigrationRow {
        final String key;
        final String layer;
        final byte[] valueBytes;

        MigrationRow(String key, String layer, byte[] valueBytes) {
            this.key = key;
            this.layer = layer;
            this.valueBytes = valueBytes;
        }
    }
}
