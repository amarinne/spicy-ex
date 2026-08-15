package com.eza.spicyex.lyrics;

/** Pure row-window selection for the bounded album-art lyric surface. */
public final class ArtworkLyricWindowPlanner {
    private ArtworkLyricWindowPlanner() {
    }

    /**
     * Selects one contiguous fitted window. {@code rowHeightsPx} must correspond to
     * {@code document.appliedLines} and include any surface-owned row spacing.
     *
     * Timed documents anchor on {@link LyricTimeline#findPrimaryActiveRow}; context expands toward
     * the less-filled side so the active row stays centered when space permits. Static documents
     * always return the first fitted page. The anchor/first row remains visible even when that one
     * row is taller than the viewport; clipping policy belongs to the adapter.
     */
    public static Window select(
            LyricsDocument document,
            long positionMs,
            int availableHeightPx,
            int[] rowHeightsPx
    ) {
        if (document == null || document.appliedLines == null || document.appliedLines.isEmpty()
                || rowHeightsPx == null || rowHeightsPx.length != document.appliedLines.size()
                || availableHeightPx <= 0) {
            return Window.empty();
        }
        if ("Static".equalsIgnoreCase(document.type)) {
            return selectStatic(document.appliedLines.size(), availableHeightPx, rowHeightsPx);
        }
        int anchor = LyricTimeline.findPrimaryActiveRow(document.appliedLines, positionMs);
        if (anchor < 0) return Window.empty();
        return selectAroundAnchor(anchor, availableHeightPx, rowHeightsPx);
    }

    private static Window selectStatic(int rowCount, int availableHeightPx, int[] heights) {
        int used = heightAt(heights, 0);
        int end = 1;
        while (end < rowCount) {
            int height = heightAt(heights, end);
            if (used + height > availableHeightPx) break;
            used += height;
            end++;
        }
        return new Window(0, end, 0, used);
    }

    private static Window selectAroundAnchor(int anchor, int availableHeightPx, int[] heights) {
        int start = anchor;
        int end = anchor + 1;
        int anchorHeight = heightAt(heights, anchor);
        int used = anchorHeight;
        if (anchorHeight >= availableHeightPx) return new Window(start, end, anchor, used);
        int sideBudget = Math.max(0, (availableHeightPx - anchorHeight) / 2);
        int beforeHeight = 0;
        int afterHeight = 0;
        while (start > 0) {
            int added = heightAt(heights, start - 1);
            if (beforeHeight + added > sideBudget) break;
            start--;
            beforeHeight += added;
        }
        while (end < heights.length) {
            int added = heightAt(heights, end);
            if (afterHeight + added > sideBudget) break;
            afterHeight += added;
            end++;
        }
        used += beforeHeight + afterHeight;
        return new Window(start, end, anchor, used);
    }

    private static int heightAt(int[] heights, int index) {
        return Math.max(0, heights[index]);
    }

    public static final class Window {
        public final int startInclusive;
        public final int endExclusive;
        public final int anchorIndex;
        public final int usedHeightPx;

        private Window(int startInclusive, int endExclusive, int anchorIndex, int usedHeightPx) {
            this.startInclusive = startInclusive;
            this.endExclusive = endExclusive;
            this.anchorIndex = anchorIndex;
            this.usedHeightPx = usedHeightPx;
        }

        public boolean isEmpty() {
            return startInclusive >= endExclusive;
        }

        public int size() {
            return Math.max(0, endExclusive - startInclusive);
        }

        private static Window empty() {
            return new Window(0, 0, -1, 0);
        }
    }
}
