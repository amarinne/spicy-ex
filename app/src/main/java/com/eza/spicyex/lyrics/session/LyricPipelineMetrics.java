package com.eza.spicyex.lyrics.session;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Privacy-safe latency and call counters for the derived-lyrics pipeline.
 *
 * <p>Records operation identity and durations only — never lyric text, provider payloads,
 * credentials, or track identifiers. Values are process-lifetime and read through
 * {@link #snapshot()} for diagnostics.
 */
public final class LyricPipelineMetrics {
    public enum Counter {
        /** Original lyrics rendered from the durable canonical cache. */
        CACHED_ORIGINAL_RENDER,
        /** Canonical base acquired from a source provider over the network. */
        FRESH_SOURCE_ACQUIRED,
        /** Canonical base replaced by a better/new source (source revision incremented). */
        SOURCE_REPLACED,
        /** Source fetches actually dispatched (after cache-first and coalescing). */
        SOURCE_FETCH_CALL,
        /** A compatible cached Sound artifact was applied without processing. */
        CACHED_SOUND_APPLIED,
        /** A Sound run completed. */
        SOUND_PROCESSED,
        /** A compatible cached Meaning artifact was applied without processing. */
        CACHED_MEANING_APPLIED,
        /** A Meaning run completed. */
        MEANING_PROCESSED,
        /** Network calls made by the Sound lane. */
        SOUND_PROVIDER_CALL,
        /**
         * Batches handed to the Meaning backend. Lines already in the per-line cache are answered
         * inside the batch without reaching the network, so this is an upper bound on requests.
         */
        MEANING_PROVIDER_CALL,
        /** Whole-document rebuild published to consumers. Should trend to zero in Phase 5. */
        DOCUMENT_REBUILD,
        /** Row-scoped derived update published instead of a rebuild. */
        LAYER_LOCAL_UPDATE,
        /** Results discarded by the stale-result guard. */
        STALE_RESULT_REJECTED,
        /** Runs that joined existing in-flight work instead of starting a duplicate. */
        COALESCED_RUN_JOINED,
        /**
         * Migration guard: the session's artifacts composed back over the canonical document did
         * not reproduce what the lanes wrote onto it. Must stay zero before publication is switched
         * to read from the session instead of the document.
         */
        COMPOSED_PROJECTION_MISMATCH
    }

    public enum Timing {
        CACHED_ORIGINAL_RENDER,
        FRESH_SOURCE,
        CACHED_SOUND_APPLY,
        SOUND_PROCESSING,
        CACHED_MEANING_APPLY,
        MEANING_PROCESSING
    }

    private static final Map<Counter, AtomicLong> COUNTERS = new EnumMap<>(Counter.class);
    private static final Map<Timing, Sample> TIMINGS = new EnumMap<>(Timing.class);

    static {
        for (Counter counter : Counter.values()) COUNTERS.put(counter, new AtomicLong());
        for (Timing timing : Timing.values()) TIMINGS.put(timing, new Sample());
    }

    private LyricPipelineMetrics() {
    }

    public static void increment(Counter counter) {
        add(counter, 1L);
    }

    public static void add(Counter counter, long delta) {
        if (counter == null || delta == 0L) return;
        COUNTERS.get(counter).addAndGet(delta);
    }

    public static long count(Counter counter) {
        return counter == null ? 0L : COUNTERS.get(counter).get();
    }

    public static void record(Timing timing, long durationMs) {
        if (timing == null || durationMs < 0L) return;
        TIMINGS.get(timing).record(durationMs);
    }

    public static long lastMs(Timing timing) {
        return timing == null ? 0L : TIMINGS.get(timing).last.get();
    }

    /** Ordered name → value view for diagnostics. Contains no user content. */
    public static Map<String, Long> snapshot() {
        Map<String, Long> out = new LinkedHashMap<>();
        for (Counter counter : Counter.values()) out.put(counter.name(), COUNTERS.get(counter).get());
        for (Timing timing : Timing.values()) {
            Sample sample = TIMINGS.get(timing);
            out.put(timing.name() + "_COUNT", sample.count.get());
            out.put(timing.name() + "_TOTAL_MS", sample.totalMs.get());
            out.put(timing.name() + "_MAX_MS", sample.maxMs.get());
            out.put(timing.name() + "_LAST_MS", sample.last.get());
        }
        return out;
    }

    public static void reset() {
        for (AtomicLong value : COUNTERS.values()) value.set(0L);
        for (Sample sample : TIMINGS.values()) sample.reset();
    }

    private static final class Sample {
        final AtomicLong count = new AtomicLong();
        final AtomicLong totalMs = new AtomicLong();
        final AtomicLong maxMs = new AtomicLong();
        final AtomicLong last = new AtomicLong();

        void record(long durationMs) {
            count.incrementAndGet();
            totalMs.addAndGet(durationMs);
            last.set(durationMs);
            long previousMax;
            do {
                previousMax = maxMs.get();
                if (durationMs <= previousMax) break;
            } while (!maxMs.compareAndSet(previousMax, durationMs));
        }

        void reset() {
            count.set(0L);
            totalMs.set(0L);
            maxMs.set(0L);
            last.set(0L);
        }
    }
}
