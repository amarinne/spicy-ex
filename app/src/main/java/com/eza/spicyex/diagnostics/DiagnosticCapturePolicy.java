package com.eza.spicyex.diagnostics;

/** Pure capture lifecycle policy kept separate from Android persistence for host tests. */
public final class DiagnosticCapturePolicy {
    private DiagnosticCapturePolicy() {
    }

    public static boolean expired(long startedWallMs, long startedElapsedMs,
                                  long nowWallMs, long nowElapsedMs, long ttlMs) {
        return startedWallMs < 0L || startedElapsedMs < 0L
                || nowWallMs < startedWallMs || nowElapsedMs < startedElapsedMs
                || nowElapsedMs - startedElapsedMs >= ttlMs;
    }

    public static boolean interruptedAfterRestore(boolean active, boolean captureFileExists) {
        return active && !captureFileExists;
    }

    public static boolean shouldRecord(long lastRecordedElapsedMs, long nowElapsedMs,
                                       long dedupeMs) {
        return lastRecordedElapsedMs < 0L || nowElapsedMs < lastRecordedElapsedMs
                || nowElapsedMs - lastRecordedElapsedMs >= dedupeMs;
    }
}
