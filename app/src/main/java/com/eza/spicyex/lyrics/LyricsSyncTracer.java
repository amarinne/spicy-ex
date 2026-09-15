package com.eza.spicyex.lyrics;

import android.util.Log;

/**
 * Opt-in diagnostic for "lyrics feel out of sync" reports (see Settings.LYRICS_SYNC_TRACER).
 * Exists to separate three different possible causes without guessing:
 *   1. The provider's own timestamps are wrong for this track (a server/data problem).
 *   2. Our own offset/processing shifted a correct timestamp (a program problem).
 *   3. Something upstream (playback position sampling, Gecko vs native backend, render pipeline)
 *      is simply reporting the position late (a latency problem, not a timestamp problem).
 * Logs to logcat under {@value #TAG}: once per document load (provider/source plus each line's
 * raw server-reported startMs - no lyric text, timestamps only) and once per active-line
 * transition (raw vs offset-adjusted playback position, the line's own startMs, and the delta
 * between them - a consistently nonzero delta at the moment of transition points at #2 or #3,
 * while comparing the logged raw startMs against what's heard by ear against open.spotify.com's
 * own lyrics for the same track isolates #1).
 */
public final class LyricsSyncTracer {
    private static final String TAG = "[LyricsSyncTracer]";

    public static volatile boolean enabled = false;

    private LyricsSyncTracer() {
    }

    public static void logDocumentLoaded(LyricsDocument document) {
        if (!enabled || document == null) return;
        Log.i(TAG, "document loaded provider=" + document.provider
                + " fetchSource=" + document.fetchSource
                + " type=" + document.type
                + " language=" + document.language
                + " lines=" + (document.appliedLines == null ? 0 : document.appliedLines.size()));
        if (document.appliedLines == null) return;
        int limit = Math.min(document.appliedLines.size(), 12);
        StringBuilder sb = new StringBuilder("raw line timings (first ").append(limit).append("):");
        for (int i = 0; i < limit; i++) {
            AppliedLine line = document.appliedLines.get(i);
            if (line == null) continue;
            sb.append(" [").append(i).append(":").append(line.startMs).append("-").append(line.endMs).append(']');
        }
        Log.i(TAG, sb.toString());
    }

    /** Logs once when the raw measured position starts/stops being frozen while playback is
     *  marked active - a real playback stall (buffering, an audio glitch), not a rendering issue.
     *  Called only on the edge (see the caller's own edge-tracking), not every frame. */
    public static void logStallChange(boolean nowStalled, long stalledForMs) {
        if (!enabled) return;
        if (nowStalled) {
            Log.w(TAG, "playback stall started - raw position frozen for " + stalledForMs + "ms while playing");
        } else {
            Log.i(TAG, "playback stall ended - raw position resumed after " + stalledForMs + "ms");
        }
    }

    public static void logTransition(int index, AppliedLine line, long rawPositionMs,
                                     long adjustedPositionMs, int syncOffsetMs,
                                     int previousDisplayedIndex, String screenProgressText) {
        if (!enabled || line == null) return;
        long delta = adjustedPositionMs - line.startMs;
        // previousDisplayedIndex/screenProgressText are what was actually on screen the instant
        // this transition was decided - not a recomputed value, the literal state just read back.
        // A transition log with a decision-time delta near 0 but a previousDisplayedIndex many
        // lines behind index means the DECISION was on time but something between deciding and
        // rendering (scroll animation, view mount, compositing) is the actual source of the lag -
        // a different bucket than what deltaAtTransition alone can show.
        Log.i(TAG, "transition index=" + index
                + " lineStartMs=" + line.startMs
                + " lineEndMs=" + line.endMs
                + " rawPositionMs=" + rawPositionMs
                + " adjustedPositionMs=" + adjustedPositionMs
                + " syncOffsetMs=" + syncOffsetMs
                + " deltaAtTransition=" + delta
                + " previousDisplayedIndex=" + previousDisplayedIndex
                + " screenProgressText=" + screenProgressText);
    }
}
