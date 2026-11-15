package com.eza.spicyex.auto;

import java.util.HashMap;
import java.util.Map;

/** A failed row keeps native content until its text, settings, or layout changes. */
final class AutoSurfaceReadiness {
    private final Map<String, String> rejected = new HashMap<>();
    private final Map<String, Boolean> previousReady = new HashMap<>();
    private final Map<String, Long> sizes = new HashMap<>();
    private final Map<String, Long> unfit = new HashMap<>();

    boolean allows(String target, String row) {
        // A box that could not fit one minimum-size page stays native until the host resizes
        // it. Rows change on every line; the box does not, so retrying each row only strips
        // native content the module cannot replace.
        Long bad = unfit.get(target);
        if (bad != null && bad.equals(sizes.get(target))) return false;
        return !row.equals(rejected.get(target));
    }

    boolean reject(String target, String row) { return !row.equals(rejected.put(target, row)); }

    /** Remember the box that fit no page, not just the row that exposed it. */
    boolean reject(String target, String row, int width, int height) {
        boolean changed = reject(target, row);
        if (width <= 0 || height <= 0) return changed;
        // Native weather reports its outer box; fitting reports its padded content box.
        // Keep the measurement owner's coordinates so the next callback is not a fake resize.
        long size = sizes.computeIfAbsent(target, key -> ((long) width << 32) | (height & 0xffffffffL));
        unfit.put(target, size);
        return changed;
    }

    void layoutChanged(String target) { rejected.remove(target); unfit.remove(target); }
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
        if (previous == null || previous == size) return false;
        unfit.remove(target);
        return rejected.remove(target) != null;
    }
}
