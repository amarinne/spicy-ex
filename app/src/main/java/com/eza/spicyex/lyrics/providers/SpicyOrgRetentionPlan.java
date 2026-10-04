package com.eza.spicyex.lyrics.providers;

/** The next cleanup follows original acquisition time, never the refresh window. */
public final class SpicyOrgRetentionPlan {
    private SpicyOrgRetentionPlan() { }

    public static long deadline(boolean hasRows, long oldestFetchMs, long newestFetchMs, long nowMs) {
        if (!hasRows) return 0;
        if (oldestFetchMs <= 0 || newestFetchMs > nowMs
                || SpicyOrgPolicy.expiredAt(oldestFetchMs, nowMs)) return nowMs;
        return oldestFetchMs > Long.MAX_VALUE - SpicyOrgPolicy.RETENTION_MS
                ? Long.MAX_VALUE : oldestFetchMs + SpicyOrgPolicy.RETENTION_MS;
    }
}
