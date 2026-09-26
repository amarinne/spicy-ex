package com.eza.spicyex.lyrics.catalog;

import com.eza.spicyex.lyrics.catalog.CatalogSource.ProviderStatus;
import com.eza.spicyex.lyrics.catalog.CatalogSource.SourceId;

import java.util.Objects;

/**
 * Persisted outcome history for one source on one track. An absent record means
 * {@link ProviderStatus#NOT_CHECKED}; in-flight work is never persisted.
 */
public final class ProviderRecord {
    public final SourceId source;
    public final ProviderStatus status;
    public final long updatedAtMs;
    public final long lastAttemptMs;
    public final long lastSuccessMs;
    /** Consecutive failed attempts since the last success; drives transient backoff. */
    public final int attemptCount;

    public ProviderRecord(SourceId source, ProviderStatus status, long updatedAtMs,
                          long lastAttemptMs, long lastSuccessMs, int attemptCount) {
        this.source = source;
        this.status = status == null ? ProviderStatus.NOT_CHECKED : status;
        this.updatedAtMs = Math.max(0L, updatedAtMs);
        this.lastAttemptMs = Math.max(0L, lastAttemptMs);
        this.lastSuccessMs = Math.max(0L, lastSuccessMs);
        this.attemptCount = Math.max(0, attemptCount);
    }

    public static ProviderRecord notChecked(SourceId source) {
        return new ProviderRecord(source, ProviderStatus.NOT_CHECKED, 0L, 0L, 0L, 0);
    }

    /** A successful answer: the source was asked and returned a usable candidate. */
    public ProviderRecord succeeded(long nowMs) {
        return new ProviderRecord(source, ProviderStatus.AVAILABLE, nowMs, nowMs, nowMs, 0);
    }

    /**
     * A terminal outcome other than success. Consecutive failures count up so transient backoff
     * grows; a durable answer (not found, rejected item) restarts the count at one.
     */
    public ProviderRecord failed(ProviderStatus outcome, long nowMs) {
        ProviderStatus next = outcome == null ? ProviderStatus.TRANSIENT_ERROR : outcome;
        int attempts = next == ProviderStatus.TRANSIENT_ERROR && status == ProviderStatus.TRANSIENT_ERROR
                ? attemptCount + 1 : 1;
        return new ProviderRecord(source, next, nowMs, nowMs, lastSuccessMs, attempts);
    }

    @Override public boolean equals(Object other) {
        if (!(other instanceof ProviderRecord)) return false;
        ProviderRecord o = (ProviderRecord) other;
        return source == o.source && status == o.status && updatedAtMs == o.updatedAtMs
                && lastAttemptMs == o.lastAttemptMs && lastSuccessMs == o.lastSuccessMs
                && attemptCount == o.attemptCount;
    }

    @Override public int hashCode() {
        return Objects.hash(source, status, updatedAtMs, lastAttemptMs, lastSuccessMs, attemptCount);
    }
}
