package com.eza.spicyex.hooks;

import java.util.Arrays;

/**
 * Fixed-capacity ring of wall-clock samples, shared by the debug clocks.
 *
 * <p>One implementation of the ring and of the percentile rule, so a second clock cannot drift
 * from the first: the samples are nanoseconds, the maximum covers every recorded sample of the
 * window, and the percentiles are nearest-rank over the samples the ring still holds. The ring
 * always keeps the most recent samples, because a new sample overwrites the oldest slot.
 *
 * <p>Not thread safe on its own: every owner synchronizes the calls it makes.
 */
final class TimingSampleRing {
    private final long[] samplesNs;
    private int next;
    private long count;
    private long maxNs;
    private long lastNs;

    TimingSampleRing(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
        samplesNs = new long[capacity];
    }

    /** A negative duration is not a measurement, so it is dropped instead of stored. */
    void record(long nanos) {
        if (nanos < 0L) return;
        count++;
        lastNs = nanos;
        if (nanos > maxNs) maxNs = nanos;
        samplesNs[next] = nanos;
        next = (next + 1) % samplesNs.length;
    }

    void reset() {
        next = 0;
        count = 0L;
        maxNs = 0L;
        lastNs = 0L;
    }

    /** Every sample recorded since the last reset, including the ones the ring has overwritten. */
    long count() {
        return count;
    }

    /** How many samples the percentiles are taken from. */
    int sampled() {
        return (int) Math.min(count, samplesNs.length);
    }

    double medianMs() {
        return percentileMs(50);
    }

    double p90Ms() {
        return percentileMs(90);
    }

    double maxMs() {
        return millis(maxNs);
    }

    double lastMs() {
        return millis(lastNs);
    }

    /** Nearest rank: the sample at {@code ceil(n * percent / 100)}, so even counts take the low
     *  middle. */
    private double percentileMs(int percent) {
        int size = sampled();
        if (size == 0) return 0.0d;
        long[] sorted = Arrays.copyOf(samplesNs, size);
        Arrays.sort(sorted);
        int rank = (int) ((size * (long) percent + 99) / 100);
        if (rank < 1) rank = 1;
        return millis(sorted[Math.min(size, rank) - 1]);
    }

    private static double millis(long nanos) {
        return Math.round(nanos / 1000.0d) / 1000.0d;
    }
}
