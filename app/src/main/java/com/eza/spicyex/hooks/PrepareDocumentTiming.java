package com.eza.spicyex.hooks;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Debug-only wall clock of the synchronous main-thread part of
 * {@link NativeSpicyShellViewImpl#prepareAndScheduleDocument(String, com.eza.spicyex.lyrics.LyricsDocument)}.
 *
 * <p>The measured body is the part that runs before the render is posted, so this is the cost a
 * track change pays on the main thread before the surface can even start mounting. Recording is
 * one timestamp pair and one synchronized store: no allocation on the hot path, and a snapshot is
 * safe to take from another thread.
 *
 * <p>{@code prepare_calls} and {@code prepare_max_ms} are exact for the whole window. Percentiles
 * use the most recent {@link #CAPACITY} calls, which is far more than a scripted track-change
 * window produces; {@code prepare_sampled} states how many samples the percentiles used.
 */
final class PrepareDocumentTiming {
    static final int CAPACITY = 4096;

    /** Loaded only when a debug path asks for the clock. */
    private static final class Instance {
        static final PrepareDocumentTiming TIMING = new PrepareDocumentTiming();
    }

    static PrepareDocumentTiming get() {
        return Instance.TIMING;
    }

    private final TimingSampleRing ring = new TimingSampleRing(CAPACITY);

    private PrepareDocumentTiming() {
    }

    synchronized void record(long nanos) {
        ring.record(nanos);
    }

    synchronized void reset() {
        ring.reset();
    }

    synchronized long count() {
        return ring.count();
    }

    /** The prepare fields of the hitch report. The names are the JSON field names: the report
     *  copies this map in as it is. */
    synchronized Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("prepare_calls", ring.count());
        out.put("prepare_sampled", (long) ring.sampled());
        out.put("prepare_median_ms", ring.medianMs());
        out.put("prepare_p90_ms", ring.p90Ms());
        out.put("prepare_max_ms", ring.maxMs());
        out.put("prepare_last_ms", ring.lastMs());
        return out;
    }
}
