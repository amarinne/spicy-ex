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
    private static final int VERSION = 1;
    private static final String TABLE_ENTRIES = "paid_entries";
    private static final String TABLE_RESERVATIONS = "reservations";
    private static final String TABLE_META = "meta";
    private static final String META_V515_MIGRATED = "v515_shared_prefs_migrated";
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
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        throw new IllegalStateException("Unsupported paid-ledger upgrade "
                + oldVersion + " -> " + newVersion);
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
        SQLiteDatabase db = getReadableDatabase();
        long size = DatabaseUtils.longForQuery(db,
                "SELECT COALESCE(MAX(raw_bytes), -1) FROM " + TABLE_ENTRIES
                        + " WHERE entry_key = ?", new String[]{key});
        if (size < 0L || size > Integer.MAX_VALUE) return null;
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

    AIPaidArtifactCache.Write put(String key, String layer, byte[] value,
                                  AIPaidArtifactCache.Reservation reservation,
                                  long maxBytes) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
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
                                             long maxBytes) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
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
            boolean removed = db.delete(TABLE_ENTRIES, "entry_key = ?", new String[]{key}) > 0;
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
            db.delete(TABLE_ENTRIES, null, null);
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
            db.delete(TABLE_ENTRIES, "layer = ?", new String[]{layer});
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
    }

    private static boolean hasMeta(SQLiteDatabase db, String key) {
        return DatabaseUtils.longForQuery(db,
                "SELECT COUNT(*) FROM " + TABLE_META + " WHERE meta_key = ?",
                new String[]{key}) > 0L;
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
