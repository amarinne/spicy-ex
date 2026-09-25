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
    /** A row filling less than this share of the available width is a stranded fragment. */
    static final int RUNT_FILL_PERCENT = 34;

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
                // A single child wider than the row still occupies exactly one line - it simply
                // overflows it. Refusing that partition used to make the whole row unplannable
                // (minLines[n] stayed INF) and dropped every OTHER word on the line back to greedy
                // wrapping, which is where the stranded one-word lines came from: one long word
                // disabled balancing for its entire row.
                if (prefix[i] - prefix[j] > availableWidth && i - j > 1) break;
                if (j > 0 && forbidden[j - 1]) continue; // break before j is a forbidden boundary
                if (minLines[j] != inf && minLines[j] + 1 < minLines[i]) minLines[i] = minLines[j] + 1;
            }
        }
        if (minLines[n] == inf) return wrapBefore;

        // Pass 2: among minimal-line layouts, minimize the sum of squared row slack (raggedness),
        // plus a penalty for any "runt" row - one left nearly empty while its neighbours are full.
        //
        // The penalty used to fire on any row holding exactly ONE child, which is the wrong
        // measure twice over. It punished a row holding a single wide word that fills the line
        // perfectly - the balanced layout was then rejected in favour of a ragged one, which is
        // how rows ended up looking like [9 words][1 word] or [1][9][1]. And it did nothing about
        // a row holding two or three tiny fragments, which strands them just as visibly. What
        // actually looks wrong is a row whose content occupies almost none of the width, so that
        // is what is measured.
        long runtPenalty = Math.max(1L, availableWidth);
        runtPenalty *= runtPenalty;
        runtPenalty = runtPenalty > Long.MAX_VALUE / 8L ? Long.MAX_VALUE / 8L : runtPenalty * 4L;
        long runtThreshold = (long) availableWidth * RUNT_FILL_PERCENT / 100L;
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
                if (line > availableWidth && i - j > 1) break;
                if (j > 0 && forbidden[j - 1]) continue;
                if (best[j] == Long.MAX_VALUE) continue;
                long slack = Math.max(0L, availableWidth - line);
                long cost = safeAdd(best[j], slack * slack);
                // Never charged against the only row of a single-row layout, which cannot be a
                // runt however short it is.
                if (n > 1 && line < runtThreshold) cost = safeAdd(cost, runtPenalty);
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

    private static long safeAdd(long left, long right) {
        if (Long.MAX_VALUE - left < right) return Long.MAX_VALUE;
        return left + right;
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
