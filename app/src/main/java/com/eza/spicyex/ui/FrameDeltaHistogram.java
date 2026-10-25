package com.eza.spicyex.ui;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bounded, allocation-free recording; snapshots may be read from any thread.
 *
 * <p>{@code max_ns} is the longest recorded frame delta. {@code over_1_5x_vsync} counts deltas
 * strictly above 1.5 display periods, the threshold a hitch has to cross to be felt as a missed
 * cadence rather than ordinary jitter.
 */
public final class FrameDeltaHistogram {
    public static final long BUCKET_NS = 100_000L;
    private static final int FINITE_BUCKETS = 2500;
    private final long[] buckets = new long[FINITE_BUCKETS + 1];
    private volatile long periodNanos;
    private final long fromNanos;
    private final long untilNanos;
    private long first, previous, callbacks, count, min = Long.MAX_VALUE, max, dropped, invalid,
            interruptions, overVsync;

    public FrameDeltaHistogram(long periodNanos, long fromNanos, long untilNanos) {
        if (periodNanos <= 0 || untilNanos <= fromNanos) throw new IllegalArgumentException("invalid window");
        this.periodNanos = periodNanos;
        this.fromNanos = fromNanos;
        this.untilNanos = untilNanos;
    }

    public synchronized void recordFrame(long frameTimeNanos) {
        if (frameTimeNanos < fromNanos || frameTimeNanos >= untilNanos) return;
        callbacks++;
        if (first == 0) first = frameTimeNanos;
        if (previous != 0) {
            long delta = frameTimeNanos - previous;
            if (delta <= 0) {
                invalid++;
                return;
            }
            int bucket = (int) Math.min(FINITE_BUCKETS, (delta - 1) / BUCKET_NS);
            buckets[bucket]++;
            count++;
            min = Math.min(min, delta);
            max = Math.max(max, delta);
            // Strictly above 1.5 display periods, without floating point in the hot path. The
            // multiplication only overflows for deltas above roughly 146 years.
            if (delta * 2L > periodNanos * 3L) overVsync++;
            // Choreographer has no loss counter. Estimate missing display slots only.
            dropped += Math.max(0L, Math.round((double) delta / periodNanos) - 1L);
        }
        previous = frameTimeNanos;
    }

    public synchronized void interrupt() {
        interruptions++;
        previous = 0;
    }

    /**
     * Debug measurement only: a display-rate change moves the 1.5x threshold for later deltas.
     * Earlier deltas keep the period that was current when they were recorded.
     */
    public synchronized void setPeriodNanos(long periodNanos) {
        if (periodNanos <= 0) throw new IllegalArgumentException("invalid period");
        this.periodNanos = periodNanos;
    }

    private long percentile(int percent) {
        if (count == 0) return 0;
        long rank = (count * percent + 99) / 100;
        long cumulative = 0;
        for (int i = 0; i < buckets.length; i++) {
            cumulative += buckets[i];
            if (cumulative >= rank) return i == FINITE_BUCKETS ? max : Math.min(max, (i + 1L) * BUCKET_NS);
        }
        return max;
    }

    /** Nearest-rank percentiles use bucket upper bounds; overflow uses the observed maximum. */
    public synchronized Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("callbacks", callbacks);
        out.put("count", count);
        out.put("min_ns", count == 0 ? 0 : min);
        out.put("p50_ns", percentile(50));
        out.put("p90_ns", percentile(90));
        out.put("p99_ns", percentile(99));
        out.put("max_ns", max);
        out.put("over_1_5x_vsync", overVsync);
        out.put("dropped_callback_estimate", dropped);
        out.put("invalid_timestamps", invalid);
        out.put("interruptions", interruptions);
        out.put("period_ns", periodNanos);
        out.put("window_ns", untilNanos - fromNanos);
        out.put("sample_span_ns", first == 0 ? 0 : previous - first);
        out.put("bucket_width_ns", BUCKET_NS);
        out.put("overflow_count", buckets[FINITE_BUCKETS]);
        out.put("buckets", buckets.clone());
        return out;
    }
}
