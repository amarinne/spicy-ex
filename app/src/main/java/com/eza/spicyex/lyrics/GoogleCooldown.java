package com.eza.spicyex.lyrics;

/**
 * Backs off from Google Translate's unofficial endpoint after it answers 429.
 *
 * <p>That endpoint rate-limits per network address, for every lane and every song at once. Asking
 * again while limited - the next batch, a retry pass, the next track - only keeps the limit in
 * place. So once it says 429, nothing is sent until the cooldown is over: the server's
 * {@code Retry-After} when given, otherwise 30s doubling on each further 429 up to 10 minutes.
 * A successful answer resets it.
 */
final class GoogleCooldown {
    static final long BASE_MS = 30_000L;
    static final long MAX_MS = 10 * 60_000L;

    private long until;
    private int strikes;

    /** Milliseconds left to wait at {@code nowMs}; 0 when requests may go out. */
    synchronized long remainingMs(long nowMs) {
        return Math.max(0L, until - nowMs);
    }

    synchronized void onRateLimited(long nowMs, long retryAfterMs) {
        long backoff = retryAfterMs > 0L
                ? Math.min(MAX_MS, retryAfterMs)
                : Math.min(MAX_MS, BASE_MS << Math.min(strikes, 10));
        strikes++;
        until = Math.max(until, nowMs + backoff);
    }

    synchronized void onSuccess() {
        strikes = 0;
        until = 0L;
    }

    /** {@code Retry-After} in ms: seconds form only (the HTTP-date form is ignored); 0 if absent. */
    static long parseRetryAfterMs(String header) {
        if (header == null) return 0L;
        try {
            long seconds = Long.parseLong(header.trim());
            return seconds > 0L ? seconds * 1000L : 0L;
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
