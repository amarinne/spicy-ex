package com.eza.spicyex.lyrics;

/**
 * Pure breakpoint planner for adaptive word-row wrapping. Given the measured outer widths of the
 * flex children and the available content width, it chooses which children start a new flex line so
 * rows are balanced instead of greedily ragged, while honoring keep-together phrase boundaries
 * derived from {@link DisplayLayoutGroup#forLine}.
 *
 * The planner contains no Android framework calls so it stays reachable from JVM tests; the
 * orchestrator is {@link GlowFlexbox}, which applies the returned flags as
 * {@code FlexboxLayout.LayoutParams.wrapBefore} on direct children only.
 */
final class AdaptiveBreakPlanner {
    private AdaptiveBreakPlanner() {
    }

    /**
     * Returns wrapBefore flags of length {@code widths.length}; index 0 is always false so the first
     * row is never empty, and child order is preserved. When no feasible plan exists (for example a
     * single child wider than the available width), returns an all-false plan so the container
     * keeps its existing greedy Flexbox behavior.
     *
     * @param widths              outer width (measured width + horizontal margins) per child, px
     * @param availableWidth      content width available to one flex line, px
     * @param forbiddenBreakAfter optional; entry i forbids a break between child i and i+1
     * @param keepTogetherGroups  optional; inclusive {first, last} child-index pairs whose members
     *                            must share a line unless the whole group overflows the row
     */
    static boolean[] plan(int[] widths, int availableWidth, boolean[] forbiddenBreakAfter,
                          int[][] keepTogetherGroups) {
        int n = widths == null ? 0 : widths.length;
        boolean[] wrapBefore = new boolean[Math.max(0, n)];
        if (n == 0 || availableWidth <= 0) return wrapBefore;
        boolean[] forbidden = relaxOversizedGroups(widths, availableWidth,
                normalize(forbiddenBreakAfter, n), keepTogetherGroups);

        long[] prefix = new long[n + 1];
        for (int i = 0; i < n; i++) prefix[i + 1] = prefix[i] + Math.max(0, widths[i]);

        // Pass 1: minimal feasible line count. INF means even the constraint set cannot be met
        // (e.g. one child alone exceeds the row) and we fall back to greedy wrapping.
        int inf = Integer.MAX_VALUE;
        int[] minLines = new int[n + 1];
        minLines[0] = 0;
        for (int i = 1; i <= n; i++) minLines[i] = inf;
        for (int i = 1; i <= n; i++) {
            for (int j = i - 1; j >= 0; j--) {
                if (prefix[i] - prefix[j] > availableWidth) break;
                if (j > 0 && forbidden[j - 1]) continue; // break before j is a forbidden boundary
                if (minLines[j] != inf && minLines[j] + 1 < minLines[i]) minLines[i] = minLines[j] + 1;
            }
        }
        if (minLines[n] == inf) return wrapBefore;

        // Pass 2: among minimal-line layouts, minimize the sum of squared row slack (raggedness).
        long[] best = new long[n + 1];
        int[] from = new int[n + 1];
        best[0] = 0;
        from[0] = -1;
        for (int i = 1; i <= n; i++) {
            best[i] = Long.MAX_VALUE;
            from[i] = -1;
            for (int j = i - 1; j >= 0; j--) {
                if (minLines[j] != minLines[i] - 1) continue;
                long line = prefix[i] - prefix[j];
                if (line > availableWidth) break;
                if (j > 0 && forbidden[j - 1]) continue;
                if (best[j] == Long.MAX_VALUE) continue;
                long slack = availableWidth - line;
                long cost = best[j] + slack * slack;
                if (cost < best[i]) {
                    best[i] = cost;
                    from[i] = j;
                }
            }
        }
        int cursor = n;
        while (cursor > 0 && from[cursor] > 0) {
            wrapBefore[from[cursor]] = true;
            cursor = from[cursor];
        }
        return wrapBefore;
    }

    /** Emergency rule: a keepTogether group wider than the row may split internally so content
     * never remains wider than the viewport. Groups that fit keep their breaks forbidden. */
    private static boolean[] relaxOversizedGroups(int[] widths, int availableWidth,
                                                  boolean[] forbidden, int[][] groups) {
        if (groups == null || groups.length == 0) return forbidden;
        boolean[] out = forbidden;
        for (int[] group : groups) {
            if (group == null || group.length < 2) continue;
            int first = Math.max(0, group[0]);
            int last = Math.min(widths.length - 1, group[1]);
            if (last <= first) continue;
            long total = 0;
            for (int i = first; i <= last; i++) total += Math.max(0, widths[i]);
            if (total <= availableWidth) continue;
            if (out == forbidden) out = forbidden.clone();
            for (int i = first; i < last; i++) out[i] = false;
        }
        return out;
    }

    private static boolean[] normalize(boolean[] forbidden, int n) {
        if (forbidden == null || forbidden.length == 0) return new boolean[Math.max(0, n)];
        if (forbidden.length >= n) return forbidden;
        boolean[] out = new boolean[n];
        System.arraycopy(forbidden, 0, out, 0, forbidden.length);
        return out;
    }
}
