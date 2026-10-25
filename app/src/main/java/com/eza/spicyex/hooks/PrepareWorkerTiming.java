package com.eza.spicyex.hooks;

import java.util.LinkedHashMap;
import java.util.Map;

/** Supplementary debug clocks. The existing caller-occupancy clock stays unchanged. */
final class PrepareWorkerTiming {
    private static final class Instance {
        static final PrepareWorkerTiming TIMING = new PrepareWorkerTiming();
    }

    static PrepareWorkerTiming get() {
        return Instance.TIMING;
    }

    private final TimingSampleRing worker = new TimingSampleRing(PrepareDocumentTiming.CAPACITY);
    private final TimingSampleRing hop = new TimingSampleRing(PrepareDocumentTiming.CAPACITY);
    private long window;

    synchronized long window() {
        return window;
    }

    synchronized void reset() {
        window++;
        worker.reset();
        hop.reset();
    }

    synchronized void recordWorker(long offeredWindow, long nanos) {
        if (offeredWindow == window) worker.record(nanos);
    }

    synchronized void recordHop(long offeredWindow, long nanos) {
        if (offeredWindow == window) hop.record(nanos);
    }

    synchronized long completedPosts() {
        return hop.count();
    }

    synchronized Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        append(out, worker, "prepare_worker");
        append(out, hop, "prepare_hop");
        return out;
    }

    private static void append(Map<String, Object> out, TimingSampleRing ring, String prefix) {
        out.put(prefix + "_count", ring.count());
        out.put(prefix + "_sampled", (long) ring.sampled());
        out.put(prefix + "_median_ms", ring.medianMs());
        out.put(prefix + "_p90_ms", ring.p90Ms());
        out.put(prefix + "_max_ms", ring.maxMs());
    }
}
