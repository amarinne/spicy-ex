package com.eza.spicyex.auto;

import com.eza.spicyex.lyrics.AppliedLine;
import com.eza.spicyex.lyrics.LyricTimeline;
import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.lyrics.session.LyricsSourcePolicy;

/** Auto display policy only. Source timings and the shared document remain unchanged. */
public final class AutoLyricPresentation {
    public static final long MIN_INTERLUDE_MS = 5000;
    public static final long INTRO_PREROLL_MS = 500;
    public static final long OUTRO_HOLD_MS = 700;
    public static final long IMMEDIATE_VOCAL_MS = 1000;
    private AutoLyricPresentation() { }

    public static boolean hasSyncedLyrics(LyricsDocument document) {
        if (document == null || !LyricsSourcePolicy.isSynced(document.type)) return false;
        for (AppliedLine line : document.appliedLines) if (vocal(line)) return true;
        return false;
    }

    public static boolean vocalActiveAt(LyricsDocument document, long position) {
        if (!hasSyncedLyrics(document)) return false;
        int index = LyricTimeline.findPrimaryActiveRow(document.appliedLines, position);
        return index >= 0 && vocal(document.appliedLines.get(index));
    }

    public static int rowAt(LyricsDocument document, long position) {
        if (!hasSyncedLyrics(document)) return -1;
        int active = LyricTimeline.findPrimaryActiveRow(document.appliedLines, position);
        if (active >= 0 && vocal(document.appliedLines.get(active))) return active;
        int previous = -1, next = -1;
        for (int i = 0; i < document.appliedLines.size(); i++) {
            AppliedLine row = document.appliedLines.get(i);
            if (!vocal(row)) continue;
            if (row.startMs <= position && (previous < 0
                    || row.startMs > document.appliedLines.get(previous).startMs)) previous = i;
            if (row.startMs > position && (next < 0
                    || row.startMs < document.appliedLines.get(next).startMs)) next = i;
        }
        // Preload the first vocal while native artwork still owns the opening window.
        if (previous < 0 && next >= 0) return next;
        if (previous >= 0 && next < 0
                && position < LyricTimeline.fillEndMs(document.appliedLines.get(previous)) + OUTRO_HOLD_MS)
            return previous;
        if (previous >= 0 && next >= 0) {
            long gap = document.appliedLines.get(next).startMs
                    - LyricTimeline.fillEndMs(document.appliedLines.get(previous));
            if (gap >= 0 && gap < MIN_INTERLUDE_MS) return previous;
        }
        return active;
    }

    public static String mode(LyricsDocument document, int index) {
        if (document == null) return "native";
        if (!hasSyncedLyrics(document)) return "note";
        if (index < 0) return "note";
        AppliedLine row = document.appliedLines.get(index);
        if (!row.dotLine) return "lyric";
        boolean before = false, after = false;
        for (AppliedLine vocal : document.appliedLines) if (vocal(vocal)) {
            before |= vocal.startMs < row.startMs;
            after |= vocal.startMs >= row.endMs;
        }
        if (!before || !after) return "native";
        return row.endMs - row.startMs >= MIN_INTERLUDE_MS ? "dots" : "note";
    }

    /** Keep native artwork during loading and the opening/closing instrumental windows. */
    public static boolean nativeArtwork(boolean resolved, boolean synced, long firstVocalStart,
            long lastVocalEnd, long position, boolean stress) {
        return nativeArtwork(resolved, synced, firstVocalStart, lastVocalEnd, position, stress, false);
    }

    public static boolean nativeArtwork(boolean resolved, boolean synced, long firstVocalStart,
            long lastVocalEnd, long position, boolean stress, boolean hasCredit) {
        if (stress) return false;
        if (resolved && AutoResponseCredit.outro(synced, lastVocalEnd, position, hasCredit)) return false;
        long takeoverAt = firstVocalStart <= IMMEDIATE_VOCAL_MS ? 0
                : firstVocalStart - INTRO_PREROLL_MS;
        return !resolved || (synced && (position < takeoverAt || position >= lastVocalEnd + OUTRO_HOLD_MS));
    }

    /** Skip only the opening acquisition, not later seeks or surface changes. */
    public static boolean immediateEntrance(boolean synced, long firstVocalStart, long position) {
        return synced && firstVocalStart <= IMMEDIATE_VOCAL_MS
                && position <= firstVocalStart + INTRO_PREROLL_MS;
    }

    private static boolean vocal(AppliedLine row) {
        return row != null && !row.dotLine && !row.bgLine && row.text != null && !row.text.trim().isEmpty();
    }
}
