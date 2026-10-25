package com.eza.spicyex.hooks;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Java heap readings for the debug mount measurements.
 *
 * <p>The used figure is the runtime's own accounting (total minus free) and the ceiling is the
 * process's maximum heap. Both are read once per measurement point - a mount or the end of a
 * phase - and never per frame, so the reading never costs more than the work it measures.
 */
final class JavaHeap {
    /** The Java heap in use right now, in kilobytes. */
    static long usedKb() {
        Runtime runtime = Runtime.getRuntime();
        return (runtime.totalMemory() - runtime.freeMemory()) / 1024L;
    }

    /** The process's maximum Java heap, in kilobytes. */
    static long maxKb() {
        return Runtime.getRuntime().maxMemory() / 1024L;
    }

    /**
     * The phase-level heap fields of the hitch report. The names are the JSON field names: the
     * report copies this map in as it is.
     */
    static Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("heap_used_kb", usedKb());
        out.put("heap_max_kb", maxKb());
        return out;
    }

    private JavaHeap() {
    }
}
