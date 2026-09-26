package com.eza.spicyex.lyrics.catalog;

import android.database.sqlite.SQLiteDatabase;

import java.util.Locale;

/**
 * Catalog usage accounting. Byte sums run against SQLite; every number the settings panel shows
 * is formatted by the pure {@link #formatReport} seam so counts and units stay JVM-tested.
 */
public final class CatalogStorage {
    private CatalogStorage() {
    }

    /** Candidate payloads plus every stored artifact. */
    static long payloadBytes(SQLiteDatabase db) {
        return sumBytes(db, null, null) + artifactBytes(db, null, null);
    }

    /** One track's candidates plus the artifacts of the lyric bodies those candidates carry. */
    static long estimateTrackBytes(SQLiteDatabase db, String trackId) {
        if (trackId == null || trackId.isEmpty()) return 0L;
        return sumBytes(db, "track_id = ?", new String[]{trackId})
                + artifactBytes(db, "canonical_digest IN (SELECT canonical_digest FROM "
                        + CatalogSchema.TABLE_CANDIDATES + " WHERE track_id = ?)",
                new String[]{trackId});
    }

    private static long sumBytes(SQLiteDatabase db, String where, String[] args) {
        if (db == null) return 0L;
        try (android.database.Cursor cursor = db.query(CatalogSchema.TABLE_CANDIDATES,
                new String[]{"COALESCE(SUM(LENGTH(normalized_document)), 0)"
                        + " + COALESCE(SUM(LENGTH(provider_transliteration)), 0)"
                        + " + COALESCE(SUM(LENGTH(raw_payload)), 0)"},
                where, args, null, null, null)) {
            return cursor.moveToFirst() ? Math.max(0L, cursor.getLong(0)) : 0L;
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private static long artifactBytes(SQLiteDatabase db, String where, String[] args) {
        if (db == null) return 0L;
        try (android.database.Cursor cursor = db.query(CatalogSchema.TABLE_ARTIFACTS,
                new String[]{"COALESCE(SUM(payload_bytes), 0)"}, where, args, null, null, null)) {
            return cursor.moveToFirst() ? Math.max(0L, cursor.getLong(0)) : 0L;
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    /** Picker/settings line: counts plus payload size. Never null, never throws. */
    public static String formatReport(int tracks, int candidates, long bytesUsed) {
        return Math.max(0, tracks) + " tracks · " + Math.max(0, candidates) + " candidates · "
                + formatBytes(bytesUsed);
    }

    public static String formatBytes(long bytes) {
        double value = Math.max(0L, bytes);
        if (value < 1024) return ((long) value) + " B";
        if (value < 1024 * 1024) return oneDecimal(value / 1024) + " KB";
        return oneDecimal(value / (1024 * 1024)) + " MB";
    }

    private static String oneDecimal(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }
}
