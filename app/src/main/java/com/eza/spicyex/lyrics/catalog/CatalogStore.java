package com.eza.spicyex.lyrics.catalog;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.eza.spicyex.Diagnostics;
import com.eza.spicyex.lyrics.catalog.CatalogSource.ProviderStatus;
import com.eza.spicyex.lyrics.catalog.CatalogSource.SourceId;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * SQLite shell over {@link CatalogSchema}: the durable owner of accepted lyric sources, user
 * decisions, and completed enrichment. Nothing here has a TTL or eviction; rows disappear only
 * through an explicit, scoped user deletion. A full disk refuses the write and reports failure.
 *
 * <p>Every track mutation goes through {@link #transact}: the track's state is read, one pure
 * {@link CatalogDecisions} function decides, and the whole write set lands in the same SQLite
 * transaction. There is no other writer for candidates, provider outcomes, rejections, or seats.
 */
public final class CatalogStore {
    private static volatile Helper helper;

    private CatalogStore() {
    }

    /** One pure decision over one track's stored state. */
    public interface Decision {
        CatalogChange decide(CatalogState state);
    }

    /** Result of one transaction. {@link #committed} is false when storage failed or refused. */
    public static final class Committed {
        public final boolean committed;
        public final CatalogState before;
        /** Null only when the state could not be read. */
        public final CatalogChange change;

        Committed(boolean committed, CatalogState before, CatalogChange change) {
            this.committed = committed;
            this.before = before;
            this.change = change;
        }

        public CatalogResolver.Resolution resolution() {
            return change == null ? null : change.resolution;
        }
    }

    private static Helper helper(Context context) {
        Helper local = helper;
        if (local != null) return local;
        synchronized (CatalogStore.class) {
            if (helper == null) {
                Context application = context.getApplicationContext();
                helper = new Helper(application == null ? context : application);
            }
            return helper;
        }
    }

    /**
     * Reads, decides, and writes one track atomically. A refused decision writes nothing and
     * reports {@code committed=false} with the unchanged resolution; a storage failure rolls back
     * every part of the change.
     */
    public static Committed transact(Context context, String trackId, Decision decision) {
        if (context == null || trackId == null || trackId.isEmpty() || decision == null) {
            return new Committed(false, null, null);
        }
        CatalogState before = null;
        CatalogChange change = null;
        try {
            SQLiteDatabase db = helper(context).getWritableDatabase();
            db.beginTransaction();
            try {
                before = readState(db, trackId);
                change = decision.decide(before);
                if (change == null || !change.accepted) {
                    return new Committed(false, before, change);
                }
                apply(db, trackId, change);
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            return new Committed(true, before, change);
        } catch (Throwable t) {
            Diagnostics.warn("CatalogStore", "transact", t);
            return new Committed(false, before, change);
        }
    }

    /** Read-only snapshot of one track; an empty state when storage is unreadable. */
    public static CatalogState state(Context context, String trackId) {
        if (context == null || trackId == null || trackId.isEmpty()) return CatalogState.empty(trackId);
        try {
            return readState(helper(context).getReadableDatabase(), trackId);
        } catch (Throwable t) {
            Diagnostics.warn("CatalogStore", "state", t);
            return CatalogState.empty(trackId);
        }
    }

    private static CatalogState readState(SQLiteDatabase db, String trackId) {
        List<CatalogCandidate> candidates = new ArrayList<>();
        try (Cursor cursor = db.query(CatalogSchema.TABLE_CANDIDATES, CANDIDATE_COLUMNS,
                "track_id = ?", new String[]{trackId}, null, null, "fetched_at_ms ASC")) {
            while (cursor.moveToNext()) {
                CatalogCandidate candidate = candidate(cursor);
                if (candidate != null) candidates.add(candidate);
            }
        }
        Map<SourceId, ProviderRecord> providers = new EnumMap<>(SourceId.class);
        try (Cursor cursor = db.query(CatalogSchema.TABLE_PROVIDER_STATES,
                new String[]{"source_id", "status", "updated_at_ms", "last_attempt_ms",
                        "last_success_ms", "attempt_count"},
                "track_id = ?", new String[]{trackId}, null, null, null)) {
            while (cursor.moveToNext()) {
                SourceId source = SourceId.parse(cursor.getString(0));
                ProviderStatus status = ProviderStatus.parse(cursor.getString(1));
                if (source == null || status == null) continue;
                long updated = cursor.getLong(2);
                // Owner-build v1 rows carry only updated_at_ms; it is the best attempt time known.
                long attempt = cursor.getLong(3) > 0L ? cursor.getLong(3) : updated;
                providers.put(source, new ProviderRecord(source, status, updated, attempt,
                        cursor.getLong(4), cursor.getInt(5)));
            }
        }
        CatalogSelection selection = null;
        try (Cursor cursor = db.query(CatalogSchema.TABLE_SELECTION,
                new String[]{"mode", "candidate_id", "source_id", "provider_item_id",
                        "last_accepted_digest"},
                "track_id = ?", new String[]{trackId}, null, null, null)) {
            if (cursor.moveToFirst()) {
                selection = new CatalogSelection(trackId,
                        CatalogSource.SelectionMode.parse(cursor.getString(0)), cursor.getString(1),
                        SourceId.parse(cursor.getString(2)), cursor.getString(3),
                        cursor.getString(4));
            }
        }
        Set<String> rejections = new HashSet<>();
        try (Cursor cursor = db.query(CatalogSchema.TABLE_REJECTIONS,
                new String[]{"source_id", "provider_item_id"},
                "track_id = ?", new String[]{trackId}, null, null, null)) {
            while (cursor.moveToNext()) {
                rejections.add(CatalogState.rejectionKey(SourceId.parse(cursor.getString(0)),
                        cursor.getString(1)));
            }
        }
        boolean known;
        try (Cursor cursor = db.query(CatalogSchema.TABLE_TRACKS, new String[]{"track_id"},
                "track_id = ?", new String[]{trackId}, null, null, null)) {
            known = cursor.moveToFirst();
        }
        return new CatalogState(trackId, candidates, providers, selection, rejections, known);
    }

    private static void apply(SQLiteDatabase db, String trackId, CatalogChange change) {
        if (change.track != null && trackId.equals(change.track.trackId)) {
            ContentValues row = new ContentValues();
            row.put("track_id", change.track.trackId);
            row.put("uri", change.track.uri);
            row.put("title", change.track.title);
            row.put("artists", change.track.artists);
            row.put("album", change.track.album);
            row.put("duration_ms", change.track.durationMs);
            row.put("last_seen_ms", change.track.lastSeenMs);
            db.replaceOrThrow(CatalogSchema.TABLE_TRACKS, null, row);
        }
        for (CatalogCandidate candidate : change.putCandidates) {
            if (!trackId.equals(candidate.trackId)) {
                throw new IllegalStateException("candidate for another track");
            }
            db.replaceOrThrow(CatalogSchema.TABLE_CANDIDATES, null, candidateRow(candidate));
        }
        Set<String> deletedDigests = new LinkedHashSet<>();
        for (String candidateId : change.deleteCandidateIds) {
            try (Cursor cursor = db.query(CatalogSchema.TABLE_CANDIDATES,
                    new String[]{"canonical_digest"}, "candidate_id = ? AND track_id = ?",
                    new String[]{candidateId, trackId}, null, null, null)) {
                if (cursor.moveToFirst()) deletedDigests.add(cursor.getString(0));
            }
            db.delete(CatalogSchema.TABLE_CANDIDATES, "candidate_id = ? AND track_id = ?",
                    new String[]{candidateId, trackId});
        }
        for (ProviderRecord record : change.putProviders) {
            if (record.source == null) continue;
            ContentValues row = new ContentValues();
            row.put("track_id", trackId);
            row.put("source_id", record.source.id);
            row.put("status", record.status.name());
            row.put("updated_at_ms", record.updatedAtMs);
            row.put("last_attempt_ms", record.lastAttemptMs);
            row.put("last_success_ms", record.lastSuccessMs);
            row.put("attempt_count", record.attemptCount);
            db.replaceOrThrow(CatalogSchema.TABLE_PROVIDER_STATES, null, row);
        }
        for (CatalogChange.Rejection rejection : change.putRejections) {
            if (rejection.source == null) continue;
            ContentValues row = new ContentValues();
            row.put("track_id", trackId);
            row.put("source_id", rejection.source.id);
            row.put("provider_item_id", rejection.providerItemId);
            row.put("rejected_at_ms", rejection.rejectedAtMs);
            db.replaceOrThrow(CatalogSchema.TABLE_REJECTIONS, null, row);
        }
        if (change.selection != null) {
            CatalogSelection selection = change.selection;
            ContentValues row = new ContentValues();
            row.put("track_id", trackId);
            row.put("mode", selection.mode.name());
            row.put("candidate_id", selection.candidateId);
            row.put("source_id", selection.sourceId == null ? "" : selection.sourceId.id);
            row.put("provider_item_id", selection.providerItemId);
            row.put("last_accepted_digest", selection.lastAcceptedDigest);
            db.replaceOrThrow(CatalogSchema.TABLE_SELECTION, null, row);
        }
        deleteOrphanArtifacts(db, deletedDigests);
    }

    /**
     * Enrichment belongs to lyric content, which several tracks can share. After an explicit
     * deletion, artifacts go only when no stored candidate anywhere still has their digest.
     */
    private static void deleteOrphanArtifacts(SQLiteDatabase db, Set<String> digests) {
        for (String digest : digests) {
            if (digest == null || digest.isEmpty()) continue;
            db.delete(CatalogSchema.TABLE_ARTIFACTS, "canonical_digest = ? AND NOT EXISTS ("
                            + "SELECT 1 FROM " + CatalogSchema.TABLE_CANDIDATES
                            + " WHERE canonical_digest = ?)",
                    new String[]{digest, digest});
        }
    }

    /**
     * Explicit whole-track deletion: candidates, outcomes, rejections, seat, metadata, and the
     * enrichment no other track shares. Returns bytes removed, or -1 when storage failed.
     */
    public static long deleteTrack(Context context, String trackId) {
        if (context == null || trackId == null || trackId.isEmpty()) return 0L;
        try {
            SQLiteDatabase db = helper(context).getWritableDatabase();
            long bytes = CatalogStorage.estimateTrackBytes(db, trackId);
            db.beginTransaction();
            try {
                Set<String> digests = new LinkedHashSet<>();
                try (Cursor cursor = db.query(CatalogSchema.TABLE_CANDIDATES,
                        new String[]{"canonical_digest"}, "track_id = ?", new String[]{trackId},
                        null, null, null)) {
                    while (cursor.moveToNext()) digests.add(cursor.getString(0));
                }
                String[] args = {trackId};
                db.delete(CatalogSchema.TABLE_CANDIDATES, "track_id = ?", args);
                db.delete(CatalogSchema.TABLE_PROVIDER_STATES, "track_id = ?", args);
                db.delete(CatalogSchema.TABLE_REJECTIONS, "track_id = ?", args);
                db.delete(CatalogSchema.TABLE_SELECTION, "track_id = ?", args);
                db.delete(CatalogSchema.TABLE_TRACKS, "track_id = ?", args);
                deleteOrphanArtifacts(db, digests);
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            return bytes;
        } catch (Throwable t) {
            Diagnostics.warn("CatalogStore", "deleteTrack", t);
            return -1L;
        }
    }

    /** Deletes every catalog row in every table. Returns false when storage failed. */
    public static boolean clearAll(Context context) {
        if (context == null) return false;
        try {
            SQLiteDatabase db = helper(context).getWritableDatabase();
            db.beginTransaction();
            try {
                db.delete(CatalogSchema.TABLE_CANDIDATES, null, null);
                db.delete(CatalogSchema.TABLE_PROVIDER_STATES, null, null);
                db.delete(CatalogSchema.TABLE_REJECTIONS, null, null);
                db.delete(CatalogSchema.TABLE_SELECTION, null, null);
                db.delete(CatalogSchema.TABLE_TRACKS, null, null);
                db.delete(CatalogSchema.TABLE_ARTIFACTS, null, null);
                db.delete(CatalogSchema.TABLE_META, null, null);
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            return true;
        } catch (Throwable t) {
            Diagnostics.warn("CatalogStore", "clearAll", t);
            return false;
        }
    }

    // --- Derived artifacts -------------------------------------------------------------------

    /** Stored artifact payload for a content key, or null. Reads never mutate. */
    public static String artifact(Context context, String key) {
        if (context == null || key == null || key.isEmpty()) return null;
        try (Cursor cursor = helper(context).getReadableDatabase().query(
                CatalogSchema.TABLE_ARTIFACTS, new String[]{"payload"}, "artifact_key = ?",
                new String[]{key}, null, null, null)) {
            return cursor.moveToFirst() ? cursor.getString(0) : null;
        } catch (Throwable t) {
            Diagnostics.warn("CatalogStore", "artifact", t);
            return null;
        }
    }

    /**
     * Stores one completed or resumable artifact. Returns false when the write failed, in which
     * case the artifact must not be reported as durable. Existing artifacts are never evicted.
     */
    public static boolean putArtifact(Context context, String key, ArtifactKind kind,
                                      String canonicalDigest, String payload) {
        if (context == null || key == null || key.isEmpty() || kind == null || payload == null
                || payload.isEmpty()) {
            return false;
        }
        try {
            ContentValues row = new ContentValues();
            row.put("artifact_key", key);
            row.put("kind", kind.name());
            row.put("canonical_digest", canonicalDigest == null ? "" : canonicalDigest);
            row.put("payload", payload);
            row.put("payload_bytes", payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
            row.put("updated_at_ms", System.currentTimeMillis());
            helper(context).getWritableDatabase()
                    .replaceOrThrow(CatalogSchema.TABLE_ARTIFACTS, null, row);
            return true;
        } catch (Throwable t) {
            Diagnostics.warn("CatalogStore", "putArtifact", t);
            return false;
        }
    }

    /** Explicit scoped deletion of one artifact kind (settings clear action). */
    public static boolean deleteArtifacts(Context context, ArtifactKind kind) {
        if (context == null || kind == null) return false;
        try {
            helper(context).getWritableDatabase().delete(CatalogSchema.TABLE_ARTIFACTS,
                    "kind = ?", new String[]{kind.name()});
            return true;
        } catch (Throwable t) {
            Diagnostics.warn("CatalogStore", "deleteArtifacts", t);
            return false;
        }
    }

    public static int artifactCount(Context context, ArtifactKind kind) {
        return count(context, CatalogSchema.TABLE_ARTIFACTS, "kind = ?",
                new String[]{kind.name()});
    }

    public static long artifactBytes(Context context, ArtifactKind kind) {
        if (context == null || kind == null) return 0L;
        try (Cursor cursor = helper(context).getReadableDatabase().query(
                CatalogSchema.TABLE_ARTIFACTS, new String[]{"COALESCE(SUM(payload_bytes), 0)"},
                "kind = ?", new String[]{kind.name()}, null, null, null)) {
            return cursor.moveToFirst() ? Math.max(0L, cursor.getLong(0)) : 0L;
        } catch (Throwable t) {
            Diagnostics.warn("CatalogStore", "artifactBytes", t);
            return 0L;
        }
    }

    // --- Reads for management UI -------------------------------------------------------------

    public static CatalogCandidate candidateById(Context context, String candidateId) {
        if (context == null || candidateId == null || candidateId.isEmpty()) return null;
        try (Cursor cursor = helper(context).getReadableDatabase().query(
                CatalogSchema.TABLE_CANDIDATES, CANDIDATE_COLUMNS, "candidate_id = ?",
                new String[]{candidateId}, null, null, null)) {
            return cursor.moveToFirst() ? candidate(cursor) : null;
        } catch (Throwable t) {
            Diagnostics.warn("CatalogStore", "candidateById", t);
            return null;
        }
    }

    /** Every manual pin across tracks; corrupt rows skipped. */
    public static List<CatalogSelection> manualSelections(Context context) {
        List<CatalogSelection> out = new ArrayList<>();
        if (context == null) return out;
        try (Cursor cursor = helper(context).getReadableDatabase().query(
                CatalogSchema.TABLE_SELECTION,
                new String[]{"track_id", "candidate_id", "source_id", "provider_item_id",
                        "last_accepted_digest"},
                "mode = ?", new String[]{CatalogSource.SelectionMode.MANUAL.name()},
                null, null, "track_id ASC")) {
            while (cursor.moveToNext()) {
                out.add(new CatalogSelection(cursor.getString(0),
                        CatalogSource.SelectionMode.MANUAL, cursor.getString(1),
                        SourceId.parse(cursor.getString(2)), cursor.getString(3),
                        cursor.getString(4)));
            }
        } catch (Throwable t) {
            Diagnostics.warn("CatalogStore", "manualSelections", t);
        }
        return out;
    }

    /** Stored track title for management rows; "" when the track row is absent. */
    public static String trackTitle(Context context, String trackId) {
        if (context == null || trackId == null || trackId.isEmpty()) return "";
        try (Cursor cursor = helper(context).getReadableDatabase().query(
                CatalogSchema.TABLE_TRACKS, new String[]{"title"},
                "track_id = ?", new String[]{trackId}, null, null, null)) {
            return cursor.moveToFirst() ? cursor.getString(0) : "";
        } catch (Throwable t) {
            Diagnostics.warn("CatalogStore", "trackTitle", t);
            return "";
        }
    }

    public static int trackCount(Context context) {
        return count(context, CatalogSchema.TABLE_TRACKS, null, null);
    }

    public static int candidateCount(Context context) {
        return count(context, CatalogSchema.TABLE_CANDIDATES, null, null);
    }

    public static long payloadBytes(Context context) {
        if (context == null) return 0L;
        try {
            return CatalogStorage.payloadBytes(helper(context).getReadableDatabase());
        } catch (Throwable t) {
            Diagnostics.warn("CatalogStore", "payloadBytes", t);
            return 0L;
        }
    }

    public static long payloadBytesForTrack(Context context, String trackId) {
        if (context == null || trackId == null || trackId.isEmpty()) return 0L;
        try {
            return CatalogStorage.estimateTrackBytes(helper(context).getReadableDatabase(), trackId);
        } catch (Throwable t) {
            Diagnostics.warn("CatalogStore", "payloadBytesForTrack", t);
            return 0L;
        }
    }

    private static int count(Context context, String table, String where, String[] args) {
        if (context == null) return 0;
        try (Cursor cursor = helper(context).getReadableDatabase().query(table,
                new String[]{"COUNT(*)"}, where, args, null, null, null)) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        } catch (Throwable t) {
            Diagnostics.warn("CatalogStore", "count", t);
            return 0;
        }
    }

    // --- Row codecs ----------------------------------------------------------------------------

    private static final String[] CANDIDATE_COLUMNS = {"candidate_id", "track_id", "source_id",
            "provider_item_id", "match_method", "match_confidence", "duration_delta_ms",
            "timing_level", "complete", "timing_healthy", "has_provider_translation",
            "has_provider_transliteration", "has_background_vocals", "has_duet", "has_credits",
            "canonical_digest", "normalized_document", "provider_transliteration", "raw_payload",
            "parser_revision", "adapter_revision", "fetched_at_ms"};

    private static CatalogCandidate candidate(Cursor cursor) {
        try {
            return new CatalogCandidate(cursor.getString(0), cursor.getString(1),
                    SourceId.parse(cursor.getString(2)), cursor.getString(3),
                    parseMatch(cursor.getString(4)), cursor.getDouble(5), cursor.getLong(6),
                    parseTiming(cursor.getString(7)), cursor.getInt(8) != 0,
                    cursor.getInt(9) != 0, cursor.getInt(10) != 0, cursor.getInt(11) != 0,
                    cursor.getInt(12) != 0, cursor.getInt(13) != 0, cursor.getInt(14) != 0,
                    cursor.getString(15), cursor.getString(16), cursor.getString(17),
                    cursor.getBlob(18), cursor.getInt(19), cursor.getInt(20), cursor.getLong(21));
        } catch (Throwable t) {
            Diagnostics.warn("CatalogStore", "candidateRow", t);
            return null;
        }
    }

    private static ContentValues candidateRow(CatalogCandidate candidate) {
        ContentValues row = new ContentValues();
        row.put("candidate_id", candidate.candidateId);
        row.put("track_id", candidate.trackId);
        row.put("source_id", candidate.sourceId == null ? "" : candidate.sourceId.id);
        row.put("provider_item_id", candidate.providerItemId);
        row.put("match_method", candidate.matchMethod.name());
        row.put("match_confidence", candidate.matchConfidence);
        row.put("duration_delta_ms", candidate.durationDeltaMs);
        row.put("timing_level", candidate.timingLevel.name());
        row.put("complete", candidate.complete ? 1 : 0);
        row.put("timing_healthy", candidate.timingHealthy ? 1 : 0);
        row.put("has_provider_translation", candidate.hasProviderTranslation ? 1 : 0);
        row.put("has_provider_transliteration", candidate.hasProviderTransliteration ? 1 : 0);
        row.put("has_background_vocals", candidate.hasBackgroundVocals ? 1 : 0);
        row.put("has_duet", candidate.hasDuet ? 1 : 0);
        row.put("has_credits", candidate.hasCredits ? 1 : 0);
        row.put("canonical_digest", candidate.canonicalDigest);
        row.put("normalized_document", candidate.normalizedDocument);
        row.put("provider_transliteration", candidate.providerTransliteration);
        row.put("raw_payload", candidate.rawPayload);
        row.put("parser_revision", candidate.parserRevision);
        row.put("adapter_revision", candidate.adapterRevision);
        row.put("fetched_at_ms", candidate.fetchedAtMs);
        return row;
    }

    private static CatalogSource.MatchMethod parseMatch(String value) {
        if (value == null) return CatalogSource.MatchMethod.STRONG_SEARCH;
        try {
            return CatalogSource.MatchMethod.valueOf(value);
        } catch (IllegalArgumentException ignored) {
            return CatalogSource.MatchMethod.STRONG_SEARCH;
        }
    }

    private static CatalogSource.TimingLevel parseTiming(String value) {
        if (value == null) return CatalogSource.TimingLevel.UNSYNCED;
        try {
            return CatalogSource.TimingLevel.valueOf(value);
        } catch (IllegalArgumentException ignored) {
            return CatalogSource.TimingLevel.UNSYNCED;
        }
    }

    private static final class Helper extends SQLiteOpenHelper {
        Helper(Context context) {
            super(context, CatalogSchema.DATABASE, null, CatalogSchema.VERSION);
            setWriteAheadLoggingEnabled(true);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            for (String sql : CatalogSchema.createAll()) db.execSQL(sql);
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            // Accepted payloads are never dropped by a schema change.
            for (String sql : CatalogSchema.migrationSql(oldVersion, newVersion)) db.execSQL(sql);
        }
    }
}
