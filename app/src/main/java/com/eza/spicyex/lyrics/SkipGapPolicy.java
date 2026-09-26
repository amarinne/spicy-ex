package com.eza.spicyex.lyrics;

import java.util.List;

/**
 * Pure gap rule for the auto-skip intro/outro feature. No Android types, no I/O.
 *
 * <p>A skippable gap is an active interlude dot row (see {@link LyricTimeline#applySyncedRows}):
 * the row planner already synthesizes dot rows for intro, mid-song, and outro-tail gaps of at
 * least {@link LyricTimeline#INTERLUDE_SHOW_THRESHOLD_MS}, and never synthesizes them for
 * approximate (Spotify-native) sources. Keying the skip off the active dot row keeps the button
 * and the rendered dots in agreement by construction.
 */
public final class SkipGapPolicy {

    /** Outro skips land this far before the track end so the seek stays inside the track. */
    public static final long OUTRO_EARLY_MS = 1000;

    private SkipGapPolicy() {
    }

    /** Which kind of gap a {@link SkipTarget} skips - drives the skip chip's label. */
    public enum GapKind {
        /** No vocal row before this gap: the track's own intro. */
        LEADING,
        /** A gap with vocals both before and after it: a mid-song instrumental break. */
        INTERLUDE,
        /** No vocal row after this gap: the outro tail. */
        TRAILING
    }

    /** A live gap: where it starts (sticky-ack key), where a skip should land, and what kind of
     *  gap it is. */
    public static final class SkipTarget {
        public final long gapStartMs;
        public final long targetMs;
        public final GapKind kind;

        SkipTarget(long gapStartMs, long targetMs, GapKind kind) {
            this.gapStartMs = gapStartMs;
            this.targetMs = targetMs;
            this.kind = kind;
        }
    }

    /**
     * Returns the skip landing for the gap active at {@code lyricPosMs}, or null when the
     * playhead is not inside a skippable gap. Intro and mid-song gaps land on the next vocal
     * start (the dot row's end); the outro tail lands {@link #OUTRO_EARLY_MS} before the track
     * end so the natural track end advances playback.
     */
    public static SkipTarget skipTarget(List<AppliedLine> rows, long lyricPosMs) {
        if (rows == null || rows.isEmpty() || lyricPosMs < 0) return null;
        int active = LyricTimeline.findPrimaryActiveRow(rows, lyricPosMs);
        if (active < 0) return null;
        AppliedLine row = rows.get(active);
        if (row == null || !row.dotLine) return null;
        long gapStart = row.startMs;
        long gapEnd = row.endMs;
        if (gapEnd <= lyricPosMs) return null;
        if (hasVocalAtOrAfter(rows, gapEnd)) {
            GapKind kind = hasVocalBefore(rows, gapStart) ? GapKind.INTERLUDE : GapKind.LEADING;
            return new SkipTarget(gapStart, gapEnd, kind);
        }
        long target = gapEnd - OUTRO_EARLY_MS;
        if (target <= lyricPosMs) return null;
        return new SkipTarget(gapStart, target, GapKind.TRAILING);
    }

    private static boolean hasVocalAtOrAfter(List<AppliedLine> rows, long gapEndMs) {
        for (AppliedLine row : rows) {
            if (row == null || row.dotLine) continue;
            if (row.startMs >= gapEndMs) return true;
        }
        return false;
    }

    private static boolean hasVocalBefore(List<AppliedLine> rows, long gapStartMs) {
        for (AppliedLine row : rows) {
            if (row == null || row.dotLine) continue;
            if (row.startMs < gapStartMs) return true;
        }
        return false;
    }

    /** Default skip-chip label per gap kind - kept here so it's covered by the same pure,
     *  unit-tested logic as the gap classification itself. */
    public static String defaultLabel(GapKind kind) {
        if (kind == null) return "Skip";
        switch (kind) {
            case LEADING: return "Skip Intro";
            case TRAILING: return "Next track";
            case INTERLUDE:
            default: return "Skip";
        }
    }
}
