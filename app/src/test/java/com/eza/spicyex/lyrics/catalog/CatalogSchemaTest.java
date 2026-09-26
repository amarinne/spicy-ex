package com.eza.spicyex.lyrics.catalog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class CatalogSchemaTest {
    @Test
    public void currentShapeCreatesEveryCatalogTable() {
        String joined = String.join("\n", CatalogSchema.createAll());
        assertTrue(joined.contains("CREATE TABLE tracks"));
        assertTrue(joined.contains("CREATE TABLE candidates"));
        assertTrue(joined.contains("CREATE TABLE provider_states"));
        assertTrue(joined.contains("CREATE TABLE selection"));
        assertTrue(joined.contains("CREATE TABLE rejections"));
        assertTrue(joined.contains("CREATE TABLE artifacts"));
        assertTrue(joined.contains("CREATE TABLE meta"));
        assertTrue(joined.contains("idx_candidates_track"));
        assertTrue(joined.contains("idx_artifacts_digest"));
        assertEquals(2, CatalogSchema.VERSION);
    }

    @Test
    public void candidateRowsCarryProvenanceAndPayloadColumns() {
        String joined = String.join("\n", CatalogSchema.createAll());
        assertTrue(joined.contains("match_method"));
        assertTrue(joined.contains("match_confidence"));
        assertTrue(joined.contains("timing_level"));
        assertTrue(joined.contains("provider_transliteration"));
        assertTrue(joined.contains("raw_payload BLOB"));
        assertTrue(joined.contains("parser_revision"));
        assertTrue(joined.contains("adapter_revision"));
    }

    @Test
    public void theSeatLivesOnlyInTheSelectionTable() {
        String tracks = CatalogSchema.createTracks("tracks");
        assertFalse(tracks.contains("auto_winner"));
        assertFalse(tracks.contains("manual_mode"));
    }

    @Test
    public void providerStatesKeepTheAttemptHistoryRetryReads() {
        String states = CatalogSchema.createProviderStates();
        assertTrue(states.contains("last_attempt_ms"));
        assertTrue(states.contains("last_success_ms"));
        assertTrue(states.contains("attempt_count"));
    }

    @Test
    public void artifactsAreKeyedByContentNotTrack() {
        String artifacts = CatalogSchema.createArtifacts();
        assertTrue(artifacts.contains("canonical_digest"));
        assertFalse(artifacts.contains("track_id"));
    }

    @Test
    public void freshDatabaseTakesTheFullPlan() {
        assertEquals(CatalogSchema.createAll(), CatalogSchema.migrationSql(0, 2));
    }

    @Test
    public void ownerBuildVersionOneKeepsRowsAndNeverDropsCandidates() {
        List<String> plan = CatalogSchema.migrationSql(1, 2);
        String joined = String.join("\n", plan);
        assertFalse(plan.isEmpty());
        assertFalse(joined.contains("DROP TABLE candidates"));
        assertFalse(joined.contains("DROP TABLE selection"));
        assertFalse(joined.contains("DROP TABLE provider_states"));
        assertTrue(joined.contains("INSERT INTO tracks_v2 SELECT"));
        assertTrue(joined.contains("CREATE TABLE rejections"));
        assertTrue(joined.contains("CREATE TABLE artifacts"));
    }

    @Test
    public void unknownOrEmptyMigrationPathsKeepTheDatabaseUntouched() {
        assertTrue(CatalogSchema.migrationSql(2, 2).isEmpty());
        assertTrue(CatalogSchema.migrationSql(3, 2).isEmpty());
        assertTrue(CatalogSchema.migrationSql(0, 99).isEmpty());
        assertTrue(CatalogSchema.migrationSql(-1, 2).isEmpty());
    }
}
