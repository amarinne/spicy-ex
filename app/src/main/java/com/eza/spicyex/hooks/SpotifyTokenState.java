package com.eza.spicyex.hooks;

/**
 * Pure JVM owner for the captured Spotify access-token lifecycle state.
 *
 * <p>Replaces the raw-token-in-a-static-string shape with an explicit state model that later
 * packets (M2 capture/persistence wiring, M3 fetch identity) consume. This class has no Android,
 * Xposed, network, logging, or persistence dependencies and must stay that way.
 *
 * <p><b>Token privacy:</b> the token text is private and never appears in {@code toString},
 * {@code equals}, {@code hashCode}, exception text, map keys, or diagnostics. The only read path
 * is {@link Authorized#token()} on the immutable snapshot returned by {@link #authorization(long)},
 * which is package-private by design: all wiring points live in this package. Snapshot equality is
 * deliberately left to identity (no {@code equals}/{@code hashCode} override) so the token can
 * never participate in comparison, hashing, or log-friendly formatting.
 *
 * <p><b>Generation policy (deliberate):</b> the generation is monotonically increasing and advances
 * only when a capture represents new usable state — a token text different from the current one.
 * Re-observing the identical token text while the current generation is healthy refreshes the
 * capture/expiry metadata but does not advance the generation, because it is the same usable epoch,
 * not a new one. Re-observing the identical text while the current generation is invalidated is
 * also a no-op: a generation rejected by an auth failure must not be resurrected by a duplicate
 * capture of the same (rejected) token; only a genuinely different token text revives the state.
 * This prevents a 401/403 retry loop driven by stale captured headers.
 *
 * <p><b>Generation-scoped invalidation:</b> {@link #invalidate(int)} tombstones only the exact
 * current generation. A stale generation number can never clear or invalidate a newer token;
 * invalidating a generation that is no longer current is rejected and returns {@code false}.
 * Tombstoning keeps the model able to distinguish "rejected epoch" from "no token" without ever
 * exposing the text.
 *
 * <p><b>Thread-safety:</b> all methods are synchronized on this instance; the token, timestamps,
 * generation, and invalidated flag are plain fields guarded by that monitor. Generation is
 * monotonic under the same lock. {@link Authorized} snapshots are immutable and safe to pass
 * across threads.
 */
final class SpotifyTokenState {
    /**
     * Requests must not be issued with a token close enough to expiry that the response window
     * straddles it. A token is treated as unusable this many milliseconds before its observed
     * expiry. Tokens with unknown expiry (<= 0) are governed only by the invalidation flag.
     */
    static final long EXPIRY_SAFETY_MARGIN_MILLIS = 60_000L;

    private String token = "";
    private long capturedAtMillis;
    private long expiresAtMillis;
    private int generation;
    private boolean invalidated;

    /**
     * Captures a newly observed token.
     *
     * @param tokenText        token text; blank input is rejected with no state change
     * @param capturedAtMillis wall-clock observation time
     * @param expiresAtMillis  observed expiry time, or any value <= 0 when unknown
     * @return true only when the capture advanced the generation (new usable state)
     */
    synchronized boolean capture(String tokenText, long capturedAtMillis, long expiresAtMillis) {
        if (isBlank(tokenText)) return false;
        if (token.equals(tokenText)) {
            if (invalidated) return false;
            // Same usable epoch: refresh freshness metadata only; no generation advance.
            this.capturedAtMillis = Math.max(this.capturedAtMillis, capturedAtMillis);
            if (expiresAtMillis > 0) this.expiresAtMillis = expiresAtMillis;
            return false;
        }
        this.token = tokenText;
        this.capturedAtMillis = capturedAtMillis;
        this.expiresAtMillis = expiresAtMillis;
        this.generation++;
        this.invalidated = false;
        return true;
    }

    /**
     * Seeds state from persisted storage (M2 restore path). Only the store calls this, and only
     * after its freshness gate accepted the persisted entry, so the token arrives pre-validated.
     * The persisted generation is kept (never advanced) so the restored epoch retains the identity
     * it was persisted with; tombstones are never persisted, so the restored epoch is healthy.
     * Blank input is rejected with no state change.
     */
    synchronized void restore(String tokenText, long capturedAtMillis, long expiresAtMillis, int generation) {
        if (isBlank(tokenText)) return;
        this.token = tokenText.trim();
        this.capturedAtMillis = capturedAtMillis;
        this.expiresAtMillis = Math.max(0L, expiresAtMillis);
        this.generation = Math.max(this.generation, Math.max(1, generation));
        this.invalidated = false;
    }

    /**
     * Tombstones exactly the current generation. Returns false — with no state change — for any
     * other generation, so a stale caller can never invalidate a newer token.
     */
    synchronized boolean invalidate(int expectedGeneration) {
        if (token.isEmpty() || invalidated || expectedGeneration != generation) return false;
        invalidated = true;
        return true;
    }

    /** True when a token is present, its generation is not tombstoned, and it is inside the expiry safety margin at {@code nowMillis}. */
    synchronized boolean isUsable(long nowMillis) {
        return !token.isEmpty() && !invalidated && !isExpiredAt(nowMillis);
    }

    /**
     * Immutable snapshot of the current usable token together with its generation, or {@code null}
     * when no usable token exists (absent, tombstoned, or inside the expiry safety margin). This is
     * the intended seam for later wiring to authorize a request and stamp it with a non-secret
     * generation for scoped invalidation.
     */
    synchronized Authorized authorization(long nowMillis) {
        if (!isUsable(nowMillis)) return null;
        return new Authorized(token, generation, capturedAtMillis, expiresAtMillis);
    }

    synchronized int generation() {
        return generation;
    }

    synchronized boolean hasToken() {
        return !token.isEmpty();
    }

    synchronized boolean isInvalidated() {
        return invalidated;
    }

    synchronized long capturedAtMillis() {
        return capturedAtMillis;
    }

    /** Observed expiry time, or 0 when unknown. */
    synchronized long expiresAtMillis() {
        return expiresAtMillis;
    }

    /** Non-secret diagnostic size; the text itself is never exposed. */
    synchronized int tokenLength() {
        return token.length();
    }

    private synchronized boolean isExpiredAt(long nowMillis) {
        return expiresAtMillis > 0
                && nowMillis >= expiresAtMillis - EXPIRY_SAFETY_MARGIN_MILLIS;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    /** Immutable snapshot binding token text to its generation for one authorized request. */
    static final class Authorized {
        private final String token;
        private final int generation;
        private final long capturedAtMillis;
        private final long expiresAtMillis;

        private Authorized(String token, int generation, long capturedAtMillis, long expiresAtMillis) {
            this.token = token;
            this.generation = generation;
            this.capturedAtMillis = capturedAtMillis;
            this.expiresAtMillis = expiresAtMillis;
        }

        /** Token text for the Authorization header; the only token read path in this model. */
        String token() {
            return token;
        }

        int generation() {
            return generation;
        }

        long capturedAtMillis() {
            return capturedAtMillis;
        }

        /** Observed expiry time, or 0 when unknown. */
        long expiresAtMillis() {
            return expiresAtMillis;
        }

        /** Re-checks this snapshot against the shared safety margin at an arbitrary clock reading. */
        boolean isExpiredAt(long nowMillis) {
            return expiresAtMillis > 0
                    && nowMillis >= expiresAtMillis - EXPIRY_SAFETY_MARGIN_MILLIS;
        }

        /** Token-length diagnostics only; the text never appears here. */
        @Override
        public String toString() {
            return "Authorized{generation=" + generation + ", tokenLength=" + token.length() + "}";
        }
    }

    /** Token-free diagnostics; the text never appears here. */
    @Override
    public String toString() {
        return "SpotifyTokenState{generation=" + generation
                + ", hasToken=" + !token.isEmpty()
                + ", invalidated=" + invalidated
                + ", tokenLength=" + token.length()
                + ", capturedAtMillis=" + capturedAtMillis
                + ", expiresAtMillis=" + expiresAtMillis + "}";
    }
}
