package com.eza.spicyex.beautifullyrics.entities;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RawLyricsCachePolicyTest {
    private static final long DAY_MS = 24L * 60L * 60L * 1000L;

    @Test
    public void evictsExpiredAndOldestEntriesBeforeRetainingWrite() {
        long now = 10L * DAY_MS;
        Map<String, Object> stored = new HashMap<>();
        add(stored, "expired", "x", now - 4L * DAY_MS);
        add(stored, "old", "xx", now - 2L * DAY_MS);
        add(stored, "newer", "xx", now - DAY_MS);

        RawLyricsCachePolicy.Decision decision = RawLyricsCachePolicy.planWrite(
                stored, "current", "xx", now, 3L * DAY_MS, 2, 100L);

        assertTrue(decision.retainWrite);
        assertTrue(decision.removedPayloadKeys.contains("expired"));
        assertTrue(decision.removedPayloadKeys.contains("old"));
        assertFalse(decision.removedPayloadKeys.contains("newer"));
    }

    @Test
    public void countsUtf8PayloadBytes() {
        long now = 10L * DAY_MS;
        Map<String, Object> stored = new HashMap<>();
        add(stored, "old", "é", now - DAY_MS);

        RawLyricsCachePolicy.Decision decision = RawLyricsCachePolicy.planWrite(
                stored, "current", "é", now, 3L * DAY_MS, 10, 3L);

        assertTrue(decision.retainWrite);
        assertTrue(decision.removedPayloadKeys.contains("old"));
    }

    @Test
    public void rejectsWriteLargerThanByteLimit() {
        RawLyricsCachePolicy.Decision decision = RawLyricsCachePolicy.planWrite(
                new HashMap<>(), "current", "12345", 100L, 100L, 32, 4L);

        assertFalse(decision.retainWrite);
    }

    @Test
    public void preservesLegacyEntryAndRequestsTimestampMigration() {
        Map<String, Object> stored = new HashMap<>();
        stored.put("legacy", "payload");

        RawLyricsCachePolicy.Decision decision = RawLyricsCachePolicy.planWrite(
                stored, "current", "new", 100L, 100L, 32, 100L);

        assertFalse(decision.removedPayloadKeys.contains("legacy"));
        assertTrue(decision.legacyPayloadKeys.contains("legacy"));
    }

    @Test
    public void replacesExistingKeyWithoutDoubleCountingIt() {
        Map<String, Object> stored = new HashMap<>();
        add(stored, "current", "old-large-value", 1L);

        RawLyricsCachePolicy.Decision decision = RawLyricsCachePolicy.planWrite(
                stored, "current", "x", 2L, 100L, 1, 1L);

        assertTrue(decision.retainWrite);
        assertFalse(decision.removedPayloadKeys.contains("current"));
    }

    private static void add(Map<String, Object> stored, String key, String value, long updatedAt) {
        stored.put(key, value);
        stored.put(key + ":updated", updatedAt);
    }
}
