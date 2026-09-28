package com.eza.spicyex.hooks;

/**
 * Coordinates layout editor reopening across configuration changes (rotation, fold posture).
 * Pure-Java policy: decision and consume-once logic are tested without Android framework mocks.
 */
final class LyricsLayoutEditorReopenPolicy {
    static final long MAX_AGE_MS = 5000L;

    static final class PendingReopen {
        final boolean cardMode;
        final String selectedName;
        final long elapsedRealtimeMs;

        PendingReopen(boolean cardMode, String selectedName, long elapsedRealtimeMs) {
            this.cardMode = cardMode;
            this.selectedName = selectedName != null ? selectedName : "";
            this.elapsedRealtimeMs = elapsedRealtimeMs;
        }
    }

    private static PendingReopen pendingReopen;

    private LyricsLayoutEditorReopenPolicy() {}

    static synchronized void record(boolean cardMode, String selectedName, long elapsedRealtimeMs) {
        pendingReopen = new PendingReopen(cardMode, selectedName, elapsedRealtimeMs);
    }

    static synchronized boolean hasPending() {
        return pendingReopen != null;
    }

    static synchronized void clear() {
        pendingReopen = null;
    }

    /**
     * Consumes the pending reopen record if it is younger than 5 seconds.
     * Clears the pending record unconditionally so it can never fire twice.
     *
     * @param nowElapsedRealtimeMs current elapsed realtime timestamp
     * @return the pending reopen if fresh, or null if absent or stale
     */
    static synchronized PendingReopen consume(long nowElapsedRealtimeMs) {
        PendingReopen record = pendingReopen;
        pendingReopen = null;
        if (record == null) {
            return null;
        }
        if (isFresh(record.elapsedRealtimeMs, nowElapsedRealtimeMs)) {
            return record;
        }
        return null;
    }

    static boolean isFresh(long recordTimestampMs, long nowTimestampMs) {
        long age = nowTimestampMs - recordTimestampMs;
        return age >= 0 && age < MAX_AGE_MS;
    }
}
