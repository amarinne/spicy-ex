package com.eza.spicyex.lyrics.session;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.eza.spicyex.Diagnostics;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Spotify-process SQLite storage for paid AI records and pre-call capacity reservations. */
final class AIPaidArtifactDatabase extends SQLiteOpenHelper {
    private static final String DATABASE = "SpicyPaidAiLedger.db";
    private static final int VERSION = 2;
    private static final String TABLE_REPLAY = "paid_replay";
    private static final String TABLE_BINDINGS = "paid_bindings";
    private static final String TABLE_ENTRIES = "paid_entries";
    private static final String TABLE_RESERVATIONS = "reservations";
    private static final String TABLE_META = "meta";
    private static final String META_V515_MIGRATED = "v515_shared_prefs_migrated";
    private static final String META_CLEAR_GENERATION = "paid_clear_generation";
    private static final int READ_CHUNK_BYTES = 256 * 1024;
    private static final long STALE_RESERVATION_MS = 6L * 60L * 60L * 1000L;

    private final Context context;

    AIPaidArtifactDatabase(Context context) {
        super(applicationContext(context), DATABASE, null, VERSION);
        this.context = applicationContext(context);
        setWriteAheadLoggingEnabled(true);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE_ENTRIES + " ("
                + "entry_key TEXT PRIMARY KEY NOT NULL,"
                + "layer TEXT NOT NULL DEFAULT '',"
                + "raw_value BLOB NOT NULL,"
                + "raw_bytes INTEGER NOT NULL,"
                + "updated_at_ms INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE " + TABLE_RESERVATIONS + " ("
                + "token TEXT PRIMARY KEY NOT NULL,"
                + "entry_key TEXT UNIQUE NOT NULL,"
                + "layer TEXT NOT NULL DEFAULT '',"
                + "reserved_bytes INTEGER NOT NULL,"
                + "touched_at_ms INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE " + TABLE_META + " ("
                + "meta_key TEXT PRIMARY KEY NOT NULL,"
                + "meta_value TEXT NOT NULL)");
        createLinkage(db);
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion != 1 || newVersion != VERSION) {
            throw new IllegalStateException("Unsupported paid-ledger upgrade " + oldVersion + " -> " + newVersion);
        }
        createLinkage(db);
        // Read one payload at a time, including payloads too large for a CursorWindow.
        try (Cursor cursor = db.query(TABLE_ENTRIES, new String[]{"entry_key"}, null, null, null, null, null)) {
            while (cursor.moveToNext()) {
                String key = cursor.getString(0);
                indexReplay(db, key, get(db, key));
            }
        }
    }

    private static void createLinkage(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE paid_replay (entry_key TEXT PRIMARY KEY NOT NULL,"
                + "layer TEXT NOT NULL, config_id TEXT NOT NULL, semantic_digest TEXT NOT NULL, baseline INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX idx_paid_replay ON paid_replay (layer, config_id, semantic_digest, baseline)");
        db.execSQL("CREATE TABLE paid_bindings (track_id TEXT NOT NULL, candidate_id TEXT NOT NULL,"
                + "route TEXT NOT NULL, upstream_source TEXT NOT NULL, provider_item_id TEXT NOT NULL,"
                + "canonical_digest TEXT NOT NULL, layer TEXT NOT NULL, config_id TEXT NOT NULL,"
                + "semantic_digest TEXT NOT NULL, entry_key TEXT NOT NULL, mapping_version INTEGER NOT NULL,"
                + "updated_at_ms INTEGER NOT NULL, PRIMARY KEY(track_id, candidate_id, layer, config_id))");
        db.execSQL("CREATE INDEX idx_paid_binding_entry ON paid_bindings (entry_key)");
    }

    private static void indexReplay(SQLiteDatabase db, String key, byte[] value) {
        db.delete(TABLE_REPLAY, "entry_key = ?", new String[]{key});
        if (value == null) return;
        com.google.gson.JsonObject index = com.eza.spicyex.lyrics.ai.AiReplayIdentity.index(key,
                new String(value, StandardCharsets.UTF_8));
        if (index == null) return;
        ContentValues row = new ContentValues();
        row.put("entry_key", key);
        row.put("layer", index.get("layer").getAsString());
        row.put("config_id", index.get("configId").getAsString());
        row.put("semantic_digest", index.get("digest").getAsString());
        row.put("baseline", index.get("baseline").getAsBoolean() ? 1 : 0);
        db.insertOrThrow(TABLE_REPLAY, null, row);
    }

    String replay(String layer, String configId, String digest, boolean baseline) {
        SQLiteDatabase db = getReadableDatabase();
        db.beginTransaction();
        try (Cursor cursor = db.rawQuery("SELECT r.entry_key FROM paid_replay r JOIN paid_entries e"
                + " ON e.entry_key = r.entry_key WHERE r.layer = ? AND r.config_id = ?"
                + " AND r.semantic_digest = ? AND r.baseline = ? ORDER BY e.updated_at_ms DESC, r.entry_key",
                new String[]{layer, configId, digest, baseline ? "1" : "0"})) {
            String payload = null;
            boolean unreadable = false;
            while (cursor.moveToNext()) {
                String key = cursor.getString(0);
                byte[] raw = get(db, key);
                com.google.gson.JsonObject index = raw == null ? null
                        : com.eza.spicyex.lyrics.ai.AiReplayIdentity.index(key, new String(raw, StandardCharsets.UTF_8));
                if (index == null || !layer.equals(index.get("layer").getAsString())
                        || !configId.equals(index.get("configId").getAsString())
                        || !digest.equals(index.get("digest").getAsString())
                        || baseline != index.get("baseline").getAsBoolean()) {
                    unreadable = true;
                    continue;
                }
                payload = com.google.gson.JsonParser.parseString(new String(raw, StandardCharsets.UTF_8))
                        .getAsJsonObject().get("payload").getAsString();
                break;
            }
            if (payload == null && unreadable) throw new IllegalStateException("replay-unreadable");
            db.setTransactionSuccessful();
            return payload;
        } finally { db.endTransaction(); }
    }

    void bind(String key, com.eza.spicyex.lyrics.ai.AiSourceBinding source,
              String layer, String configId, String digest) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            if (DatabaseUtils.longForQuery(db, "SELECT COUNT(*) FROM paid_replay WHERE entry_key = ?"
                    + " AND layer = ? AND config_id = ? AND semantic_digest = ?",
                    new String[]{key, layer, configId, digest}) == 0L) return;
            ContentValues row = new ContentValues();
            row.put("track_id", source.trackId); row.put("candidate_id", source.candidateId);
            row.put("route", source.route); row.put("upstream_source", source.upstream);
            row.put("provider_item_id", source.providerItemId); row.put("canonical_digest", source.canonicalDigest);
            row.put("layer", layer); row.put("config_id", configId); row.put("semantic_digest", digest);
            row.put("entry_key", key); row.put("mapping_version", 1); row.put("updated_at_ms", System.currentTimeMillis());
            db.insertWithOnConflict(TABLE_BINDINGS, null, row, SQLiteDatabase.CONFLICT_REPLACE);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    boolean ensureV515Migration() {
        SQLiteDatabase db = getWritableDatabase();
        if (hasMeta(db, META_V515_MIGRATED)) return true;
        synchronized (AIPaidArtifactCache.LOCK) {
            if (hasMeta(db, META_V515_MIGRATED)) return true;
            SharedPreferences preferences = context.getSharedPreferences(
                    AIPaidArtifactCache.LEGACY_PREFS, Context.MODE_PRIVATE);
            List<AIPaidArtifactCache.MigrationRow> rows =
                    AIPaidArtifactCache.migrationRows(preferences.getAll());
            db.beginTransaction();
            try {
                for (AIPaidArtifactCache.MigrationRow row : rows) {
                    putRaw(db, row.key, row.layer, row.valueBytes, row.valueBytes.length);
                }
                long count = DatabaseUtils.longForQuery(db,
                        "SELECT COUNT(*) FROM " + TABLE_ENTRIES, null);
                if (count != rows.size()) {
                    throw new IllegalStateException("v515 migration count mismatch");
                }
                ContentValues marker = new ContentValues();
                marker.put("meta_key", META_V515_MIGRATED);
                marker.put("meta_value", String.valueOf(rows.size()));
                db.insertOrThrow(TABLE_META, null, marker);
                db.setTransactionSuccessful();
                Diagnostics.event("AIPaidArtifactDatabase", "v515_migrated",
                        Diagnostics.context("entries", String.valueOf(rows.size())));
                return true;
            } catch (Throwable failure) {
                Diagnostics.warn("AIPaidArtifactDatabase", "migrate_v515", failure);
                return false;
            } finally {
                db.endTransaction();
            }
        }
    }

    byte[] get(String key) {
        return get(getReadableDatabase(), key);
    }

    private static byte[] get(SQLiteDatabase db, String key) {
        long size = DatabaseUtils.longForQuery(db,
                "SELECT COALESCE(MAX(raw_bytes), -1) FROM " + TABLE_ENTRIES
                        + " WHERE entry_key = ?", new String[]{key});
        if (size < 0L || size > Integer.MAX_VALUE) return null;
        if (DatabaseUtils.longForQuery(db, "SELECT length(raw_value) FROM paid_entries WHERE entry_key = ?",
                new String[]{key}) != size) return null;
        ByteArrayOutputStream output = new ByteArrayOutputStream((int) size);
        int offset = 1;
        while (output.size() < size) {
            int requested = (int) Math.min(READ_CHUNK_BYTES, size - output.size());
            try (Cursor cursor = db.rawQuery(
                    "SELECT substr(raw_value, ?, ?) FROM " + TABLE_ENTRIES
                            + " WHERE entry_key = ?",
                    new String[]{String.valueOf(offset), String.valueOf(requested), key})) {
                if (!cursor.moveToFirst()) return null;
                byte[] chunk = cursor.getBlob(0);
                if (chunk == null || chunk.length == 0) return null;
                output.write(chunk, 0, chunk.length);
                offset += chunk.length;
            }
        }
        return output.toByteArray();
    }

    AIPaidArtifactCache.Read read(String key, String layer, PaidArtifactIdentity identity) {
        SQLiteDatabase db = getReadableDatabase();
        db.beginTransaction();
        try {
            byte[] raw = get(db, key);
            String payload = AIPaidArtifactCache.payloadOf(raw, identity);
            AIPaidArtifactCache.Read read = new AIPaidArtifactCache.Read(
                    payload, key, layer,
                    clearGeneration(db, META_CLEAR_GENERATION),
                    clearGeneration(db, layerGenerationKey(layer)),
                    clearGeneration(db, entryGenerationKey(key)), payload != null
                            || DatabaseUtils.longForQuery(db, "SELECT COUNT(*) FROM paid_entries WHERE entry_key = ?",
                                    new String[]{key}) == 0L);
            db.setTransactionSuccessful();
            return read;
        } finally {
            db.endTransaction();
        }
    }

    AIPaidArtifactCache.Write put(String key, String layer, byte[] value,
                                  AIPaidArtifactCache.Reservation reservation,
                                  AIPaidArtifactCache.Read read, long maxBytes) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            if (isRevoked(db, key, layer, read)) {
                return AIPaidArtifactCache.Write.rejected("run-revoked");
            }
            long reserved = matchingReservationBytes(db, key, reservation);
            long target = Math.max(value.length, reserved);
            long other = footprintExcluding(db, key);
            String rejection = AIPaidArtifactCache.admissionReason(other, target, maxBytes);
            if (!rejection.isEmpty()) return AIPaidArtifactCache.Write.rejected(rejection);
            putRaw(db, key, layer, value, value.length);
            db.setTransactionSuccessful();
            return AIPaidArtifactCache.Write.OK;
        } catch (Throwable failure) {
            Diagnostics.warn("AIPaidArtifactDatabase", "put", failure);
            return AIPaidArtifactCache.Write.rejected("write-failed");
        } finally {
            db.endTransaction();
        }
    }

    AIPaidArtifactCache.Reservation reserve(String key, String layer, long maxRecordBytes,
                                             AIPaidArtifactCache.Reservation current,
                                             AIPaidArtifactCache.Read read, long maxBytes) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            if (isRevoked(db, key, layer, read)) {
                return AIPaidArtifactCache.Reservation.rejected(
                        AIPaidArtifactCache.Reservation.Status.UNAVAILABLE, "run-revoked");
            }
            long now = System.currentTimeMillis();
            db.delete(TABLE_RESERVATIONS, "touched_at_ms < ?",
                    new String[]{String.valueOf(now - STALE_RESERVATION_MS)});
            ReservationRow held = reservationForKey(db, key);
            if (held != null && (current == null || !held.token.equals(current.token))) {
                return AIPaidArtifactCache.Reservation.rejected(
                        AIPaidArtifactCache.Reservation.Status.BUSY, "storage-busy");
            }
            long actual = entryBytes(db, key);
            long requested = Math.max(actual, Math.max(0L, maxRecordBytes));
            long other = footprintExcluding(db, key);
            String rejection = AIPaidArtifactCache.admissionReason(other, requested, maxBytes);
            if (!rejection.isEmpty()) {
                return AIPaidArtifactCache.Reservation.rejected(
                        AIPaidArtifactCache.Reservation.Status.FULL, rejection);
            }
            String token = current != null && current.accepted()
                    ? current.token : java.util.UUID.randomUUID().toString();
            ContentValues values = new ContentValues();
            values.put("token", token);
            values.put("entry_key", key);
            values.put("layer", layer);
            values.put("reserved_bytes", requested);
            values.put("touched_at_ms", now);
            if (db.insertWithOnConflict(TABLE_RESERVATIONS, null, values,
                    SQLiteDatabase.CONFLICT_REPLACE) == -1L) {
                throw new IllegalStateException("reservation insert failed");
            }
            db.setTransactionSuccessful();
            return AIPaidArtifactCache.Reservation.admitted(token, key);
        } catch (Throwable failure) {
            Diagnostics.warn("AIPaidArtifactDatabase", "reserve", failure);
            return AIPaidArtifactCache.Reservation.rejected(
                    AIPaidArtifactCache.Reservation.Status.UNAVAILABLE, "storage-unavailable");
        } finally {
            db.endTransaction();
        }
    }

    void release(AIPaidArtifactCache.Reservation reservation) {
        if (reservation == null || !reservation.accepted()) return;
        try {
            getWritableDatabase().delete(TABLE_RESERVATIONS, "token = ? AND entry_key = ?",
                    new String[]{reservation.token, reservation.entryKey});
        } catch (Throwable failure) {
            Diagnostics.warn("AIPaidArtifactDatabase", "release", failure);
        }
    }

    boolean remove(String key) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete(TABLE_RESERVATIONS, "entry_key = ?", new String[]{key});
            db.delete(TABLE_BINDINGS, "entry_key = ?", new String[]{key});
            db.delete(TABLE_REPLAY, "entry_key = ?", new String[]{key});
            boolean removed = db.delete(TABLE_ENTRIES, "entry_key = ?", new String[]{key}) > 0;
            bumpClearGeneration(db, entryGenerationKey(key));
            db.setTransactionSuccessful();
            return removed;
        } finally {
            db.endTransaction();
        }
    }

    void clear() {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete(TABLE_RESERVATIONS, null, null);
            db.delete(TABLE_BINDINGS, null, null);
            db.delete(TABLE_REPLAY, null, null);
            db.delete(TABLE_ENTRIES, null, null);
            bumpClearGeneration(db, META_CLEAR_GENERATION);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    void clearLayer(String layer) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete(TABLE_RESERVATIONS, "layer = ?", new String[]{layer});
            db.delete(TABLE_BINDINGS, "layer = ?", new String[]{layer});
            db.delete(TABLE_REPLAY, "layer = ?", new String[]{layer});
            db.delete(TABLE_ENTRIES, "layer = ?", new String[]{layer});
            bumpClearGeneration(db, layerGenerationKey(layer));
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    AIPaidArtifactCache.Stats stats(long maxBytes) {
        SQLiteDatabase db = getReadableDatabase();
        int entries = (int) DatabaseUtils.longForQuery(db,
                "SELECT COUNT(*) FROM " + TABLE_ENTRIES, null);
        long bytes = DatabaseUtils.longForQuery(db,
                "SELECT COALESCE(SUM(raw_bytes), 0) FROM " + TABLE_ENTRIES, null);
        return new AIPaidArtifactCache.Stats(entries, bytes, Integer.MAX_VALUE, maxBytes);
    }

    com.google.gson.JsonObject linkageStatus(String trackId) {
        SQLiteDatabase db = getReadableDatabase();
        db.beginTransaction();
        try {
            com.google.gson.JsonObject out = new com.google.gson.JsonObject();
            out.addProperty("schema", db.getVersion());
            for (String table : new String[]{TABLE_ENTRIES, TABLE_REPLAY, TABLE_BINDINGS, TABLE_RESERVATIONS}) {
                out.addProperty(table, DatabaseUtils.longForQuery(db, "SELECT COUNT(*) FROM " + table, null));
            }
            out.addProperty("orphan_bindings", DatabaseUtils.longForQuery(db,
                    "SELECT COUNT(*) FROM paid_bindings b LEFT JOIN paid_entries e ON b.entry_key=e.entry_key"
                            + " WHERE e.entry_key IS NULL", null));
            out.addProperty("payload_bytes", usageBytes());
            com.google.gson.JsonArray bindings = new com.google.gson.JsonArray();
            try (Cursor cursor = db.query(TABLE_BINDINGS,
                    new String[]{"candidate_id", "route", "upstream_source", "provider_item_id", "layer", "entry_key"},
                    "track_id = ?", new String[]{trackId}, null, null, "layer, candidate_id")) {
                while (cursor.moveToNext()) {
                    com.google.gson.JsonObject binding = new com.google.gson.JsonObject();
                    for (int i = 0; i < cursor.getColumnCount(); i++) {
                        binding.addProperty(cursor.getColumnName(i), cursor.getString(i));
                    }
                    bindings.add(binding);
                }
            }
            out.add("track_bindings", bindings);
            db.setTransactionSuccessful();
            return out;
        } finally { db.endTransaction(); }
    }

    com.google.gson.JsonArray entryList() {
        com.google.gson.JsonArray entries = new com.google.gson.JsonArray();
        try (Cursor cursor = getReadableDatabase().query(TABLE_ENTRIES,
                new String[]{"entry_key", "layer", "raw_bytes", "updated_at_ms"},
                null, null, null, null, "updated_at_ms DESC, entry_key")) {
            while (cursor.moveToNext()) {
                com.google.gson.JsonObject entry = new com.google.gson.JsonObject();
                entry.addProperty("entry_key", cursor.getString(0));
                entry.addProperty("layer", cursor.getString(1));
                entry.addProperty("bytes", cursor.getLong(2));
                entry.addProperty("updated_at_ms", cursor.getLong(3));
                entries.add(entry);
            }
        }
        return entries;
    }

    /** Stored payload bytes only; used for the settings panel usage display. */
    long usageBytes() {
        return DatabaseUtils.longForQuery(getReadableDatabase(),
                "SELECT COALESCE(SUM(raw_bytes), 0) FROM " + TABLE_ENTRIES, null);
    }

    private static void putRaw(SQLiteDatabase db, String key, String layer, byte[] value, long bytes) {
        ContentValues values = new ContentValues();
        values.put("entry_key", key);
        values.put("layer", layer == null ? "" : layer);
        values.put("raw_value", value);
        values.put("raw_bytes", bytes);
        values.put("updated_at_ms", System.currentTimeMillis());
        if (db.insertWithOnConflict(TABLE_ENTRIES, null, values,
                SQLiteDatabase.CONFLICT_REPLACE) == -1L) {
            throw new IllegalStateException("paid entry insert failed");
        }
        indexReplay(db, key, value);
    }

    private static boolean hasMeta(SQLiteDatabase db, String key) {
        return DatabaseUtils.longForQuery(db,
                "SELECT COUNT(*) FROM " + TABLE_META + " WHERE meta_key = ?",
                new String[]{key}) > 0L;
    }

    private static boolean isRevoked(SQLiteDatabase db, String key, String layer,
                                     AIPaidArtifactCache.Read read) {
        return isRevoked(read, key, layer,
                clearGeneration(db, META_CLEAR_GENERATION),
                clearGeneration(db, layerGenerationKey(layer)),
                clearGeneration(db, entryGenerationKey(key)));
    }

    static boolean isRevoked(AIPaidArtifactCache.Read read, String key, String layer,
                             long allGeneration, long layerGeneration, long entryGeneration) {
        return read == null || !read.admissionSafe || !read.matches(key, layer, allGeneration,
                layerGeneration, entryGeneration);
    }

    private static String layerGenerationKey(String layer) {
        return META_CLEAR_GENERATION + ":layer:" + layer;
    }

    private static String entryGenerationKey(String key) {
        return META_CLEAR_GENERATION + ":entry:" + key;
    }

    private static long clearGeneration(SQLiteDatabase db, String key) {
        return DatabaseUtils.longForQuery(db,
                "SELECT COALESCE(MAX(CAST(meta_value AS INTEGER)), 0) FROM " + TABLE_META
                        + " WHERE meta_key = ?", new String[]{key});
    }

    private static void bumpClearGeneration(SQLiteDatabase db, String key) {
        ContentValues values = new ContentValues();
        values.put("meta_key", key);
        values.put("meta_value", String.valueOf(clearGeneration(db, key) + 1L));
        if (db.insertWithOnConflict(TABLE_META, null, values,
                SQLiteDatabase.CONFLICT_REPLACE) == -1L) {
            throw new IllegalStateException("clear generation insert failed");
        }
    }

    private static long entryBytes(SQLiteDatabase db, String key) {
        return DatabaseUtils.longForQuery(db,
                "SELECT COALESCE(MAX(raw_bytes), 0) FROM " + TABLE_ENTRIES
                        + " WHERE entry_key = ?", new String[]{key});
    }

    private static long matchingReservationBytes(SQLiteDatabase db, String key,
                                                  AIPaidArtifactCache.Reservation reservation) {
        if (reservation == null || !reservation.accepted()) return 0L;
        return DatabaseUtils.longForQuery(db,
                "SELECT COALESCE(MAX(reserved_bytes), 0) FROM " + TABLE_RESERVATIONS
                        + " WHERE entry_key = ? AND token = ?",
                new String[]{key, reservation.token});
    }

    private static ReservationRow reservationForKey(SQLiteDatabase db, String key) {
        try (Cursor cursor = db.query(TABLE_RESERVATIONS,
                new String[]{"token"}, "entry_key = ?",
                new String[]{key}, null, null, null)) {
            return cursor.moveToFirst()
                    ? new ReservationRow(cursor.getString(0)) : null;
        }
    }

    /** Actual entries plus the larger reserved footprint for every other identity. */
    private static long footprintExcluding(SQLiteDatabase db, String excludedKey) {
        long unreserved = DatabaseUtils.longForQuery(db,
                "SELECT COALESCE(SUM(e.raw_bytes), 0) FROM " + TABLE_ENTRIES + " e"
                        + " WHERE e.entry_key <> ? AND NOT EXISTS (SELECT 1 FROM "
                        + TABLE_RESERVATIONS + " r WHERE r.entry_key = e.entry_key)",
                new String[]{excludedKey});
        long reserved = DatabaseUtils.longForQuery(db,
                "SELECT COALESCE(SUM(CASE WHEN r.reserved_bytes > COALESCE(e.raw_bytes, 0)"
                        + " THEN r.reserved_bytes ELSE COALESCE(e.raw_bytes, 0) END), 0) FROM "
                        + TABLE_RESERVATIONS + " r LEFT JOIN " + TABLE_ENTRIES
                        + " e ON e.entry_key = r.entry_key WHERE r.entry_key <> ?",
                new String[]{excludedKey});
        return AIPaidArtifactCache.safeAdd(unreserved, reserved);
    }

    private static final class ReservationRow {
        final String token;
        ReservationRow(String token) {
            this.token = token;
        }
    }

    private static Context applicationContext(Context context) {
        Context app = context.getApplicationContext();
        return app != null ? app : context;
    }
}
