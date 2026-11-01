package com.eza.spicyex.hooks;

import com.eza.spicyex.lyrics.AppliedLine;
import com.eza.spicyex.lyrics.LyricTimeline;

import java.util.List;

/**
 * Pure clock math for the now-playing card editor's preview: where the loop sits, and which
 * line start Replay or the next-line tap should jump to.
 */
final class CardPreviewClock {
    private CardPreviewClock() {
    }

    /** The loop position for one frame, clamped and wrapped into the document. */
    static long position(long nowMs, long startMs, long durationMs) {
        long elapsed = Math.max(0L, nowMs - startMs);
        return durationMs <= 0L ? 0L : elapsed % durationMs;
    }

    /**
     * The start of the line that contains the position. In a gap, the last line that already
     * started wins, so Replay shows a lyric again instead of an empty interlude.
     */
    static long lineStartAt(List<AppliedLine> lines, long positionMs) {
        int index = activeIndex(lines, positionMs);
        if (index >= 0) return Math.max(0L, lines.get(index).startMs);
        long best = Long.MIN_VALUE;
        if (lines != null) {
            for (AppliedLine line : lines) {
                if (line != null && line.startMs <= positionMs) best = Math.max(best, line.startMs);
            }
        }
        if (best != Long.MIN_VALUE) return Math.max(0L, best);
        return lines == null || lines.isEmpty() ? 0L : Math.max(0L, lines.get(0).startMs);
    }

    /**
     * The start of the next line, wrapping to the first line after the last one. In a gap, the
     * first line that starts after the position wins.
     */
    static long nextLineStartAt(List<AppliedLine> lines, long positionMs) {
        if (lines == null || lines.isEmpty()) return 0L;
        int index = activeIndex(lines, positionMs);
        if (index >= 0) return Math.max(0L, lines.get((index + 1) % lines.size()).startMs);
        long next = Long.MAX_VALUE;
        for (AppliedLine line : lines) {
            if (line != null && line.startMs > positionMs && line.startMs < next) {
                next = line.startMs;
            }
        }
        return next == Long.MAX_VALUE ? Math.max(0L, lines.get(0).startMs) : next;
    }

    private static int activeIndex(List<AppliedLine> lines, long positionMs) {
        if (lines == null || lines.isEmpty() || positionMs < 0L) return -1;
        int index = LyricTimeline.findPrimaryActiveRow(lines, positionMs);
        return index >= 0 && index < lines.size() ? index : -1;
    }
}
