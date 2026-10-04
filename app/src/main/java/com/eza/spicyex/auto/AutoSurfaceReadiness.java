package com.eza.spicyex.auto;

import java.util.HashMap;
import java.util.Map;

/** A failed row keeps native content until its text, settings, or layout changes. */
final class AutoSurfaceReadiness {
    private final Map<String, String> rejected = new HashMap<>();
    private final Map<String, Boolean> previousReady = new HashMap<>();
    private final Map<String, Long> sizes = new HashMap<>();

    boolean allows(String target, String row) { return !row.equals(rejected.get(target)); }
    boolean reject(String target, String row) { return !row.equals(rejected.put(target, row)); }
    void layoutChanged(String target) { rejected.remove(target); }
    long measuredSize(String target) { return sizes.getOrDefault(target, 0L); }

    /** A removed lyric view cannot announce that a later row is ready to mount. */
    boolean changed(String target, String row, boolean eligible) {
        boolean ready = eligible && allows(target, row);
        return ready != Boolean.TRUE.equals(previousReady.put(target, ready));
    }
    /** Native and lyric subtrees share this host measurement; remounting alone is not a resize. */
    boolean measured(String target, int width, int height) {
        if (width <= 0 || height <= 0) return false;
        long size = ((long) width << 32) | (height & 0xffffffffL);
        Long previous = sizes.put(target, size);
        return previous != null && previous != size && rejected.remove(target) != null;
    }
}
