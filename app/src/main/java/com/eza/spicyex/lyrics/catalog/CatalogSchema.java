package com.eza.spicyex.lyrics.catalog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Dedicated catalog database shape. This store must never drop accepted data on upgrade: accepted
 * payloads and completed enrichment have no TTL and disappear only through explicit deletion. The
 * generic {@code SpicyLyricCaches.db} drops its tables on upgrade and is unsuitable for this data.
 *
 * <p>Version 2 is the first released shape. Version 1 existed only on owner test builds; its one
 * upgrade step keeps those rows and moves the seat to the {@code selection} table alone.
 */
public final class CatalogSchema {
    public static final String DATABASE = "LyricsCatalog.db";
    public static final int VERSION = 2;

    static final String TABLE_TRACKS = "tracks";
    static final String TABLE_CANDIDATES = "candidates";
    static final String TABLE_PROVIDER_STATES = "provider_states";
    static final String TABLE_SELECTION = "selection";
    static final String TABLE_REJECTIONS = "rejections";
    static final String TABLE_ARTIFACTS = "artifacts";
    static final String TABLE_META = "meta";

    private CatalogSchema() {
    }

    /** Track metadata only. The seat lives in {@code selection} and nowhere else. */
    static String createTracks(String table) {
        return "CREATE TABLE " + table + " ("
                + "track_id TEXT PRIMARY KEY NOT NULL,"
                + "uri TEXT NOT NULL,"
                + "title TEXT NOT NULL,"
                + "artists TEXT NOT NULL,"
                + "album TEXT NOT NULL,"
                + "duration_ms INTEGER NOT NULL,"
                + "last_seen_ms INTEGER NOT NULL)";
    }

    static String createCandidates() {
        return "CREATE TABLE " + TABLE_CANDIDATES + " ("
                + "candidate_id TEXT PRIMARY KEY NOT NULL,"
                + "track_id TEXT NOT NULL,"
                + "source_id TEXT NOT NULL,"
                + "provider_item_id TEXT NOT NULL,"
                + "match_method TEXT NOT NULL,"
                + "match_confidence REAL NOT NULL,"
                + "duration_delta_ms INTEGER NOT NULL,"
                + "timing_level TEXT NOT NULL,"
                + "complete INTEGER NOT NULL,"
                + "timing_healthy INTEGER NOT NULL,"
                + "has_provider_translation INTEGER NOT NULL,"
                + "has_provider_transliteration INTEGER NOT NULL,"
                + "has_background_vocals INTEGER NOT NULL,"
                + "has_duet INTEGER NOT NULL,"
                + "has_credits INTEGER NOT NULL,"
                + "canonical_digest TEXT NOT NULL,"
                + "normalized_document TEXT NOT NULL,"
                + "provider_transliteration TEXT NOT NULL,"
                + "raw_payload BLOB NOT NULL,"
                + "parser_revision INTEGER NOT NULL,"
                + "adapter_revision INTEGER NOT NULL,"
                + "fetched_at_ms INTEGER NOT NULL)";
    }

    static String createCandidatesIndex() {
        return "CREATE INDEX idx_candidates_track ON " + TABLE_CANDIDATES + " (track_id)";
    }

    /** Per-track per-source outcome plus the attempt history the retry policy reads. */
    static String createProviderStates() {
        return "CREATE TABLE " + TABLE_PROVIDER_STATES + " ("
                + "track_id TEXT NOT NULL,"
                + "source_id TEXT NOT NULL,"
                + "status TEXT NOT NULL,"
                + "updated_at_ms INTEGER NOT NULL,"
                + "last_attempt_ms INTEGER NOT NULL DEFAULT 0,"
                + "last_success_ms INTEGER NOT NULL DEFAULT 0,"
                + "attempt_count INTEGER NOT NULL DEFAULT 0,"
                + "PRIMARY KEY (track_id, source_id))";
    }

    static String createSelection() {
        return "CREATE TABLE " + TABLE_SELECTION + " ("
                + "track_id TEXT PRIMARY KEY NOT NULL,"
                + "mode TEXT NOT NULL,"
                + "candidate_id TEXT NOT NULL,"
                + "source_id TEXT NOT NULL,"
                + "provider_item_id TEXT NOT NULL,"
                + "last_accepted_digest TEXT NOT NULL)";
    }

    /**
     * A user rejection names one provider item for one track. Every content version of that item
     * is refused; other items from the same provider stay eligible.
     */
    static String createRejections() {
        return "CREATE TABLE " + TABLE_REJECTIONS + " ("
                + "track_id TEXT NOT NULL,"
                + "source_id TEXT NOT NULL,"
                + "provider_item_id TEXT NOT NULL,"
                + "rejected_at_ms INTEGER NOT NULL,"
                + "PRIMARY KEY (track_id, source_id, provider_item_id))";
    }

    /**
     * Derived Sound, Meaning, and detection artifacts. Keyed by content, not by track: the key
     * carries the canonical digest and the producing configuration, so every track sharing one
     * lyric body shares its enrichment and a candidate switch never deletes the previous body's.
     */
    static String createArtifacts() {
        return "CREATE TABLE " + TABLE_ARTIFACTS + " ("
                + "artifact_key TEXT PRIMARY KEY NOT NULL,"
                + "kind TEXT NOT NULL,"
                + "canonical_digest TEXT NOT NULL,"
                + "payload TEXT NOT NULL,"
                + "payload_bytes INTEGER NOT NULL,"
                + "updated_at_ms INTEGER NOT NULL)";
    }

    static String createArtifactsIndex() {
        return "CREATE INDEX idx_artifacts_digest ON " + TABLE_ARTIFACTS + " (canonical_digest)";
    }

    static String createMeta() {
        return "CREATE TABLE " + TABLE_META + " ("
                + "meta_key TEXT PRIMARY KEY NOT NULL,"
                + "meta_value TEXT NOT NULL)";
    }

    /** Full current shape, in creation order. */
    public static List<String> createAll() {
        List<String> out = new ArrayList<>();
        out.add(createTracks(TABLE_TRACKS));
        out.add(createCandidates());
        out.add(createCandidatesIndex());
        out.add(createProviderStates());
        out.add(createSelection());
        out.add(createRejections());
        out.add(createArtifacts());
        out.add(createArtifactsIndex());
        out.add(createMeta());
        return Collections.unmodifiableList(out);
    }

    /**
     * Migration plan from {@code oldVersion} to {@code newVersion}. Returns an empty plan when there
     * is nothing to do or the path is unknown; the caller then keeps the existing database untouched
     * rather than risking a half-translated store.
     */
    public static List<String> migrationSql(int oldVersion, int newVersion) {
        if (oldVersion < 0 || newVersion > VERSION || oldVersion >= newVersion) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<>();
        if (oldVersion == 0) {
            out.addAll(createAll());
        } else if (oldVersion == 1) {
            // Owner-build v1: keep candidates, pins, and statuses; drop the duplicated seat
            // columns from tracks by rebuilding it.
            out.add("ALTER TABLE " + TABLE_PROVIDER_STATES
                    + " ADD COLUMN last_attempt_ms INTEGER NOT NULL DEFAULT 0");
            out.add("ALTER TABLE " + TABLE_PROVIDER_STATES
                    + " ADD COLUMN last_success_ms INTEGER NOT NULL DEFAULT 0");
            out.add("ALTER TABLE " + TABLE_PROVIDER_STATES
                    + " ADD COLUMN attempt_count INTEGER NOT NULL DEFAULT 0");
            out.add(createTracks("tracks_v2"));
            out.add("INSERT INTO tracks_v2 SELECT track_id, uri, title, artists, album,"
                    + " duration_ms, last_seen_ms FROM " + TABLE_TRACKS);
            out.add("DROP TABLE " + TABLE_TRACKS);
            out.add("ALTER TABLE tracks_v2 RENAME TO " + TABLE_TRACKS);
            out.add(createRejections());
            out.add(createArtifacts());
            out.add(createArtifactsIndex());
        }
        if (out.isEmpty()) return Collections.emptyList();
        return Collections.unmodifiableList(out);
    }
}
