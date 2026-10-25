package com.eza.spicyex.hooks;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Debug-only wall clock of the main-thread commit-and-mount callback.
 *
 * <p>This is the block a track change pays on the main thread after the document was prepared
 * off the main thread: the prepared document is assigned and its lyric rows are created, bound and
 * laid out. It is the one part of the handoff no trace section marked, so the callback is also
 * the {@code SpicyMount} section in {@link NativeSpicyShellViewImpl}.
 *
 * <p>Recording is one timestamp pair, one heap reading and one synchronized store: no allocation
 * on the hot path, and a snapshot is safe to take from another thread.
 *
 * <p>{@code mount_calls} and {@code mount_max_ms} are exact for the whole window. Percentiles use
 * the most recent {@link #CAPACITY} mounts; {@code mount_sampled} states how many samples the
 * percentiles used. The heap fields catch a mount that leaks: {@code mount_heap_used_kb} is the
 * heap after the last mount and {@code mount_heap_growth_kb} is that value minus the heap after
 * the first mount of the window.
 */
final class MountDocumentTiming {
    static final int CAPACITY = PrepareDocumentTiming.CAPACITY;

    /** Loaded only when a debug path asks for the clock. */
    private static final class Instance {
        static final MountDocumentTiming TIMING = new MountDocumentTiming();
    }

    static MountDocumentTiming get() {
        return Instance.TIMING;
    }

    private final TimingSampleRing durations = new TimingSampleRing(CAPACITY);
    private long firstHeapKb = -1L;
    private long lastHeapKb = -1L;

    MountDocumentTiming() {
    }

    /** Records a finished mount and the heap it left behind. */
    synchronized void record(long nanos) {
        record(nanos, JavaHeap.usedKb());
    }

    /** Records a finished mount with a heap reading the caller already took. */
    synchronized void record(long nanos, long heapUsedKb) {
        if (nanos < 0L) return;
        durations.record(nanos);
        if (firstHeapKb < 0L) firstHeapKb = heapUsedKb;
        lastHeapKb = heapUsedKb;
    }

    synchronized void reset() {
        durations.reset();
        firstHeapKb = -1L;
        lastHeapKb = -1L;
    }

    /** The mount fields of the hitch report. The names are the JSON field names: the report
     *  copies this map in as it is. */
    synchronized Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mount_calls", durations.count());
        out.put("mount_sampled", (long) durations.sampled());
        out.put("mount_median_ms", durations.medianMs());
        out.put("mount_p90_ms", durations.p90Ms());
        out.put("mount_max_ms", durations.maxMs());
        out.put("mount_last_ms", durations.lastMs());
        out.put("mount_heap_used_kb", Math.max(0L, lastHeapKb));
        out.put("mount_heap_growth_kb", heapGrowthKb());
        return out;
    }

    /** How much the heap grew between the first and the last mount of the window. */
    private long heapGrowthKb() {
        if (firstHeapKb < 0L || lastHeapKb < 0L) return 0L;
        return lastHeapKb - firstHeapKb;
    }
}
