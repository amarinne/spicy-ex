package com.eza.spicyex.lyrics;

import java.io.IOException;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;

/** Transport-only breaker. Leases prevent late responses from undoing a newer transition. */
final class SpicyCircuitBreaker {
    static final long MAX_PAUSE = 2_700_000;
    private static final long[] LADDER = {30000,30000,30000,60000,120000,120000,120000,
            120000,120000,120000,120000,120000,300000};
    interface Store {
        long[] load();
        void save(long openUntil, int ladderIndex, long lastTripAt, long lastProbeAt);
    }
    private final Store store;
    private final LongSupplier clock;
    private final DoubleSupplier random;
    private long openUntil, lastTripAt, lastProbeAt, probeStartedAt, activeProbe, sequence, generation;
    private int ladderIndex, failures;

    SpicyCircuitBreaker(Store store, LongSupplier clock, DoubleSupplier random) {
        this.store = store;
        this.clock = clock;
        this.random = random;
        long[] state = store.load();
        openUntil = Math.max(0, state[0]);
        ladderIndex = (int) Math.max(0, Math.min(LADDER.length - 1, state[1]));
        lastTripAt = state[2];
        lastProbeAt = state[3];
        long now = clock.getAsLong();
        if (openUntil - now > MAX_PAUSE) { openUntil = 0; ladderIndex = 0; lastTripAt = 0; }
        if (lastTripAt > now) lastTripAt = 0;
        if (lastProbeAt > now) lastProbeAt = 0;
        if (now - lastTripAt > 3_600_000) ladderIndex = 0;
        save();
    }

    static final class Lease {
        final int kind; // 0 normal, 1 half-open, 2 early user probe.
        final long token, generation;
        boolean settled;
        Lease(int kind, long token, long generation) {
            this.kind = kind; this.token = token; this.generation = generation;
        }
    }
    static final class Suppressed extends IOException {
        final long retryAfterMs;
        Suppressed(long delay) { super("Spicy rate-limited: request suppressed"); retryAfterMs = delay; }
    }
    synchronized Lease acquire(boolean userProbe) throws Suppressed {
        long now = clock.getAsLong();
        if (activeProbe != 0 && now - probeStartedAt > 60000) activeProbe = 0;
        if (now < openUntil) {
            if (!userProbe || activeProbe != 0) throw new Suppressed(openUntil - now);
            if (now - lastProbeAt < 30000) throw new Suppressed(30000 - (now - lastProbeAt));
            return probe(2, now);
        }
        if (openUntil != 0) {
            if (activeProbe != 0) throw new Suppressed(30000);
            return probe(1, now);
        }
        return new Lease(0, 0, generation);
    }
    private Lease probe(int kind, long now) {
        activeProbe = ++sequence;
        probeStartedAt = lastProbeAt = now;
        save();
        return new Lease(kind, activeProbe, generation);
    }
    private boolean claim(Lease lease) {
        if (lease.settled) return false;
        lease.settled = true;
        if (lease.kind != 0) {
            if (lease.token != activeProbe) return false;
            activeProbe = 0;
        }
        return lease.generation == generation;
    }
    synchronized void success(Lease lease) {
        if (!claim(lease)) return;
        failures = 0;
        if (openUntil != 0 || ladderIndex != 0) {
            generation++;
            openUntil = 0; ladderIndex = 0;
            save();
        }
    }
    synchronized void failure(Lease lease, Long retryAfter) {
        if (!claim(lease) || lease.kind == 2) return;
        if (lease.kind == 0 && ++failures < 2) return;
        long pause = retryAfter == null ? (long) (LADDER[ladderIndex] * (0.5 + random.getAsDouble())) : retryAfter;
        long now = clock.getAsLong();
        openUntil = now + Math.max(0, Math.min(MAX_PAUSE, pause));
        lastTripAt = now;
        ladderIndex = Math.min(ladderIndex + 1, LADDER.length - 1);
        generation++; failures = 0;
        save();
    }
    private void save() { store.save(openUntil, ladderIndex, lastTripAt, lastProbeAt); }
    static boolean tripStatus(int status) {
        return status == 403 || status == 408 || status == 425 || status == 429
                || status >= 500 && status <= 599;
    }
    static Long retryAfter(String header, long now) {
        if (header == null) return null;
        try {
            double seconds = Double.parseDouble(header.trim());
            if (Double.isFinite(seconds) && seconds > 0) return (long) Math.min(MAX_PAUSE, seconds * 1000);
        } catch (NumberFormatException ignored) { }
        try {
            long delay = ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - now;
            return delay > 0 ? Math.min(MAX_PAUSE, delay) : null;
        } catch (RuntimeException ignored) { return null; }
    }
}
