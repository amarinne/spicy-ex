package com.eza.spicyex.lyrics;

import com.google.gson.JsonObject;

import com.eza.spicyex.lyrics.session.DetectionArtifact;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ProcessedLyricsCacheDetectionTest {
    @Test
    public void currentIdentityMatches() {
        JsonObject record = ProcessedLyricsCache.newDetectionRecordHeader(
                "digest-a", DetectionArtifact.DETECTOR_POLICY_ID, true);

        assertTrue(ProcessedLyricsCache.detectionRecordMatches(record, "digest-a"));
    }

    @Test
    public void digestMismatchIsRejected() {
        JsonObject record = ProcessedLyricsCache.newDetectionRecordHeader(
                "digest-a", DetectionArtifact.DETECTOR_POLICY_ID, true);

        assertFalse(ProcessedLyricsCache.detectionRecordMatches(record, "digest-b"));
    }

    @Test
    public void detectionSchemaBumpInvalidatesTheRecord() {
        JsonObject record = ProcessedLyricsCache.newDetectionRecordHeader(
                "digest-a", DetectionArtifact.DETECTOR_POLICY_ID, true);
        record.addProperty("detectionSchemaVersion", DetectionArtifact.SCHEMA_VERSION + 1);

        assertFalse(ProcessedLyricsCache.detectionRecordMatches(record, "digest-a"));
    }

    @Test
    public void detectorPolicyChangeInvalidatesTheRecord() {
        JsonObject record = ProcessedLyricsCache.newDetectionRecordHeader(
                "digest-a", DetectionArtifact.DETECTOR_POLICY_ID, true);
        record.addProperty("detectorPolicyId", "some-other-policy");

        assertFalse(ProcessedLyricsCache.detectionRecordMatches(record, "digest-a"));
    }

    @Test
    public void recordSchemaAndKindAreValidated() {
        JsonObject record = ProcessedLyricsCache.newDetectionRecordHeader(
                "digest-a", DetectionArtifact.DETECTOR_POLICY_ID, true);
        record.addProperty("kind", "SOUND");

        assertFalse(ProcessedLyricsCache.detectionRecordMatches(record, "digest-a"));
    }

    @Test
    public void detectionKeyCarriesDigestAndSchemaVersion() {
        assertEquals("detection/abc/" + DetectionArtifact.SCHEMA_VERSION,
                LyricCaches.detectionArtifactKey("abc", DetectionArtifact.SCHEMA_VERSION));
    }

    @Test
    public void detectionIdentityCarriesNoNetworkOrProcessingEpoch() {
        JsonObject record = ProcessedLyricsCache.newDetectionRecordHeader(
                "digest-a", DetectionArtifact.DETECTOR_POLICY_ID, true);

        // A network-cache epoch bump or an app/processing version change must never invalidate a
        // detection record. Nothing version-like beyond the detection schema and policy may appear.
        assertFalse(record.has("networkEpoch"));
        assertFalse(record.has("processingVersion"));
        assertFalse(record.has("readingSchemaVersion"));
        assertTrue(ProcessedLyricsCache.detectionRecordMatches(record, "digest-a"));
    }

    @Test
    public void providerDetectionRecordNeedsNoCanonicalDigest() {
        JsonObject record = new JsonObject();
        record.addProperty("schema", 1);
        record.addProperty("kind", "PROVIDER_DETECTION");
        record.addProperty("detectionSchemaVersion", DetectionArtifact.SCHEMA_VERSION);
        record.addProperty("detectorPolicyId", DetectionArtifact.DETECTOR_POLICY_ID);

        assertTrue(ProcessedLyricsCache.providerDetectionRecordMatches(record));

        record.addProperty("kind", "DETECTION");
        assertFalse(ProcessedLyricsCache.providerDetectionRecordMatches(record));
    }

    @Test
    public void providerDetectionKeyIsTextScoped() {
        String first = LyricCaches.providerDetectionKey("Hola amigo");
        String second = LyricCaches.providerDetectionKey("Hello friend");

        assertEquals(first, LyricCaches.providerDetectionKey("Hola amigo"));
        assertFalse(first.equals(second));
        assertTrue(first.startsWith("detection/text/"));
    }
}
