package com.eza.spicyex.lyrics;

import java.util.List;

/**
 * Pure timing normalization and renderer row planning, extracted from the native Spicy hook so
 * the logic is unit-testable. No Android types, no I/O.
 *
 * Pipeline: source adapters parse {@link LyricsLine}s -> {@link #rebalanceStaticTimings} +
 * {@link #fillMissingEndTimes} normalize timing -> {@link #applySyncedRows} plans
 * {@link AppliedLine} rows (vocals, background vocals, interlude dot rows) -> the renderer
 * mounts/animates rows and asks {@link #findPrimaryActiveRow}/{@link #isRowActiveAt} per frame.
 */
public final class LyricTimeline {

    /** Dot rows fade out this long before their window ends (pre-hide, Spicy parity). */
    public static final long PRE_HIDDEN_DOT_LINE_MS = 500;
    /** Gaps at least this long are instrumental interludes and get a dot row. */
    public static final long INTERLUDE_SHOW_THRESHOLD_MS = 3000;
    /** Fallback duration for a vocal line whose real end time is unknown. */
    public static final long DEFAULT_LINE_DURATION_MS = 3500;

    private LyricTimeline() {
    }

    /** Spread synthetic static-lyrics timings across the track instead of a fixed cadence. */
    public static void rebalanceStaticTimings(LyricsDocument doc) {
        if (doc == null || !"Static".equalsIgnoreCase(doc.type) || doc.lines.isEmpty()) return;
        long interval = DEFAULT_LINE_DURATION_MS;
        if (doc.durationMs > 30000) {
            interval = Math.max(1800, Math.min(6000, doc.durationMs / Math.max(1, doc.lines.size())));
        }
        for (int i = 0; i < doc.lines.size(); i++) {
            LyricsLine line = doc.lines.get(i);
            line.startMs = i * interval;
            line.endMs = (i + 1) * interval;
        }
    }

    /**
     * Fill end times for sources that only carry start times (LRCLIB, Spotify native lines).
     *
     * Rules:
     * - An INTERLUDE MARKER always extends to the next line's start so its dot row spans the
     *   whole instrumental, however long it is.
     * - A VOCAL line extends to the next start only when the gap is small (below
     *   {@link #INTERLUDE_SHOW_THRESHOLD_MS}); a large gap is an instrumental break that must
     *   stay open so {@link #applySyncedRows} can synthesize a dot row instead of the vocal
     *   highlight swallowing it.
     * - Lines that already carry a real end time (endMs > startMs) are left untouched, so
     *   adapters MUST NOT pre-fill synthetic end times (the old LRCLIB adapter did, which
     *   disabled all of the above).
     */
    public static void fillMissingEndTimes(List<LyricsLine> lines) {
        if (lines == null) return;
        for (int i = 0; i < lines.size(); i++) {
            LyricsLine line = lines.get(i);
            if (line == null || line.endMs > line.startMs) continue;
            long next = 0;
            for (int j = i + 1; j < lines.size(); j++) {
                LyricsLine candidate = lines.get(j);
                if (candidate != null && candidate.startMs > line.startMs) {
                    next = candidate.startMs;
                    break;
                }
            }
            if (next > line.startMs) {
                long gap = next - line.startMs;
                if (line.interlude || gap < INTERLUDE_SHOW_THRESHOLD_MS) {
                    line.endMs = next;
                } else {
                    line.endMs = line.startMs + DEFAULT_LINE_DURATION_MS;
                }
            } else {
                line.endMs = line.startMs + DEFAULT_LINE_DURATION_MS;
            }
        }
    }

    /** Rebuild {@code doc.appliedLines} (the renderer row plan) from {@code doc.lines}. */
    public static void applySyncedRows(LyricsDocument doc) {
        if (doc == null) return;
        doc.appliedLines.clear();
        if (doc.lines == null || doc.lines.isEmpty()) return;

        if ("Syllable".equalsIgnoreCase(doc.type) || "Line".equalsIgnoreCase(doc.type)) {
            applyTimedRows(doc);
        } else {
            for (LyricsLine line : doc.lines) {
                if (line == null) continue;
                doc.appliedLines.add(createAppliedVocalRow(line, line.startMs, line.endMs));
            }
        }
    }

    private static void applyTimedRows(LyricsDocument doc) {
        int firstVocal = firstNonInterludeIndex(doc.lines);
        if (firstVocal >= 0) {
            LyricsLine first = doc.lines.get(firstVocal);
            // Key the intro dot row off the FIRST VOCAL line's start, not doc.startTimeMs —
            // several sources never set startTimeMs. Matches Spicy desktop behavior.
            if (first.startMs >= INTERLUDE_SHOW_THRESHOLD_MS
                    && !hasExplicitInterludeBetween(doc.lines, 0, firstVocal - 1)) {
                doc.appliedLines.add(createAppliedDotRow(0, first.startMs, first.oppositeAligned));
            }
        }

        for (int i = 0; i < doc.lines.size(); i++) {
            LyricsLine line = doc.lines.get(i);
            if (line == null) continue;
            if (line.interlude) {
                doc.appliedLines.add(createAppliedDotRow(line.startMs, line.endMs, line.oppositeAligned));
                continue;
            }
            int nextIndex = nextNonInterludeIndex(doc.lines, i + 1);
            long nextStartMs = nextIndex >= 0 ? doc.lines.get(nextIndex).startMs : 0;
            // Extend the row's ACTIVE window across small gaps so the highlight/scroll-follow
            // carries to the next line instead of dropping to "no active row" between lines.
            // The karaoke fill still uses the source line's own end (fillEndMs()).
            long appliedEndMs = resolveAppliedEndMs(line.endMs, nextStartMs);
            doc.appliedLines.add(createAppliedVocalRow(line, line.startMs, appliedEndMs));
            for (BackgroundLine bg : line.backgroundLines) {
                if (bg == null) continue;
                doc.appliedLines.add(createAppliedBackgroundRow(line, bg));
            }
            if (nextIndex >= 0 && !hasExplicitInterludeBetween(doc.lines, i + 1, nextIndex - 1)) {
                LyricsLine next = doc.lines.get(nextIndex);
                if (next.startMs - line.endMs >= INTERLUDE_SHOW_THRESHOLD_MS) {
                    doc.appliedLines.add(createAppliedDotRow(line.endMs, next.startMs, next.oppositeAligned));
                }
            }
        }
        // End-of-song interlude: a long instrumental tail between the last lyric line and the track
        // end gets its own dot row (no following line would otherwise trigger one).
        if (doc.durationMs > 0 && !doc.lines.isEmpty()) {
            LyricsLine last = doc.lines.get(doc.lines.size() - 1);
            if (!last.interlude && doc.durationMs - last.endMs >= INTERLUDE_SHOW_THRESHOLD_MS) {
                doc.appliedLines.add(createAppliedDotRow(last.endMs, doc.durationMs, last.oppositeAligned));
            }
        }
    }

    /** Extend a vocal row's active end to the next start when the gap is small. */
    static long resolveAppliedEndMs(long endMs, long nextStartMs) {
        if (nextStartMs <= endMs) return endMs;
        long gap = nextStartMs - endMs;
        if (gap < INTERLUDE_SHOW_THRESHOLD_MS) return nextStartMs;
        return endMs;
    }

    /**
     * End time the karaoke fill/gradient should run to. {@code endMs} may be extended into the
     * following gap for active-window purposes; the fill must finish at the line's real end.
     */
    public static long fillEndMs(AppliedLine row) {
        if (row == null) return 0;
        if (row.sourceLine != null && row.sourceLine.endMs > row.startMs) return row.sourceLine.endMs;
        return row.endMs;
    }

    /** A row is active while the playhead is inside its (possibly extended) window. */
    public static boolean isRowActiveAt(AppliedLine row, long positionMs) {
        return row != null && positionMs >= row.startMs && positionMs < row.endMs;
    }

    /**
     * The row the renderer should treat as THE active line (scroll anchor, highlight,
     * CurrentLyricState). Multiple rows can be time-active at once (lead + background vocal,
     * lines extended across a gap); prefer the most recently started lead vocal, then fall back
     * to whatever else is active (dot/background row).
     *
     * Animation must NOT key off this single index — animate every row for which
     * {@link #isRowActiveAt} holds, otherwise concurrent background vocals never fill.
     */
    public static int findPrimaryActiveRow(List<AppliedLine> rows, long positionMs) {
        if (rows == null || rows.isEmpty() || positionMs < 0) return -1;
        int bestLead = -1;
        int firstOther = -1;
        for (int i = 0; i < rows.size(); i++) {
            AppliedLine row = rows.get(i);
            if (!isRowActiveAt(row, positionMs)) continue;
            if (!row.bgLine && !row.dotLine) {
                if (bestLead < 0 || row.startMs >= rows.get(bestLead).startMs) bestLead = i;
            } else if (firstOther < 0) {
                firstOther = i;
            }
        }
        return bestLead >= 0 ? bestLead : firstOther;
    }

    public static int firstNonInterludeIndex(List<LyricsLine> lines) {
        return nextNonInterludeIndex(lines, 0);
    }

    public static int nextNonInterludeIndex(List<LyricsLine> lines, int start) {
        if (lines == null) return -1;
        for (int i = Math.max(0, start); i < lines.size(); i++) {
            LyricsLine line = lines.get(i);
            if (line != null && !line.interlude) return i;
        }
        return -1;
    }

    static boolean hasExplicitInterludeBetween(List<LyricsLine> lines, int start, int end) {
        if (lines == null) return false;
        for (int i = Math.max(0, start); i <= end && i < lines.size(); i++) {
            LyricsLine line = lines.get(i);
            if (line != null && line.interlude) return true;
        }
        return false;
    }

    static AppliedLine createAppliedVocalRow(LyricsLine source, long startMs, long endMs) {
        AppliedLine row = new AppliedLine();
        row.sourceLine = source;
        row.text = LyricsDocument.safe(source.text);
        row.romanizedText = LyricsDocument.safe(source.romanizedText);
        row.translatedText = LyricsDocument.safe(source.translatedText);
        row.japaneseReading = source.japaneseReading;
        row.startMs = Math.max(0, startMs);
        row.endMs = Math.max(row.startMs + 1, endMs);
        row.totalMs = row.endMs - row.startMs;
        row.oppositeAligned = source.oppositeAligned;
        row.bgLine = false;
        if (source.syllables != null) {
            for (SyllableSegment seg : source.syllables) row.words.add(copySegment(seg, false));
        }
        return row;
    }

    static AppliedLine createAppliedBackgroundRow(LyricsLine source, BackgroundLine bg) {
        AppliedLine row = new AppliedLine();
        row.sourceLine = source;
        row.text = LyricsDocument.safe(bg.text);
        row.romanizedText = LyricsDocument.safe(bg.romanizedText);
        row.translatedText = LyricsDocument.safe(bg.translatedText);
        row.startMs = Math.max(0, bg.startMs);
        row.endMs = Math.max(row.startMs + 1, bg.endMs);
        row.totalMs = row.endMs - row.startMs;
        row.oppositeAligned = source.oppositeAligned;
        row.bgLine = true;
        for (SyllableSegment seg : bg.syllables) row.words.add(copySegment(seg, true));
        return row;
    }

    static AppliedLine createAppliedDotRow(long startMs, long endMs, boolean oppositeAligned) {
        AppliedLine row = new AppliedLine();
        row.dotLine = true;
        row.text = "• • •";
        row.startMs = Math.max(0, startMs);
        row.endMs = Math.max(row.startMs + 1, endMs);
        row.totalMs = row.endMs - row.startMs;
        row.oppositeAligned = oppositeAligned;

        double baseDotTime = row.totalMs / 3d;
        double dotPadding = ((PRE_HIDDEN_DOT_LINE_MS + 50d) * -1d) / 3d;
        long dot1End = Math.max(row.startMs, Math.round(row.startMs + baseDotTime + dotPadding));
        long dot2End = Math.max(dot1End, Math.round(row.startMs + baseDotTime * 2d + dotPadding * 2d));
        long dot3End = Math.max(dot2End, Math.round(row.startMs + row.totalMs + ((PRE_HIDDEN_DOT_LINE_MS + 50d) * -1d)));

        row.words.add(createDotWord(row.startMs, dot1End));
        row.words.add(createDotWord(dot1End, dot2End));
        row.words.add(createDotWord(dot2End, dot3End));
        return row;
    }

    private static SyllableSegment createDotWord(long startMs, long endMs) {
        SyllableSegment seg = new SyllableSegment();
        seg.text = "•";
        seg.startMs = startMs;
        seg.endMs = Math.max(startMs, endMs);
        seg.totalMs = Math.max(0, seg.endMs - seg.startMs);
        seg.dot = true;
        return seg;
    }

    static SyllableSegment copySegment(SyllableSegment source, boolean bgWord) {
        SyllableSegment seg = SyllableSegment.copyOf(source);
        if (seg == null) return new SyllableSegment();
        seg.bgWord = bgWord;
        return seg;
    }
}
