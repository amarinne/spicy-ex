package com.eza.spicyex.lyrics;

import android.util.Log;
import android.view.View;

import java.util.WeakHashMap;

/**
 * Opt-in diagnostic for visual glitches in the lyrics animation pipeline (see
 * Settings.ANIM_CONFLICT_LOGGER). Two independent problems have both turned out, live, to look
 * like "flicker": (1) two different code paths writing a row's RenderEffect/alpha/etc in the same
 * rendered frame, where whichever wrote last silently won depending on unrelated caching in each
 * path, and (2) a value snapping by more than an eased step should in a single frame instead of
 * interpolating. Both are invisible in a debugger (they only show up as a look on screen) but both
 * leave a trail here. Logs to logcat under {@value #TAG} - filter for it while reproducing a glitch
 * instead of guessing from a video frame-by-frame.
 */
public final class AnimTracer {
    private static final String TAG = "[LyricsAnimTracer]";

    /** Cheap early-out for every call site when the setting is off; refreshed alongside config. */
    public static volatile boolean enabled = false;

    private static final WeakHashMap<View, Entry> lastWrite = new WeakHashMap<>();
    private static long frameId = 0;

    private AnimTracer() {
    }

    /** Call once per LyricsFrameRenderer.applySynced invocation, before touching any row. */
    public static void beginFrame() {
        if (!enabled) return;
        frameId++;
    }

    /**
     * Call from every site that sets a View's RenderEffect directly (bypassing a shared batcher
     * would be the usual reason two sites both touch the same view). Logs when a *different*
     * source touched the same view's RenderEffect within the same frame.
     */
    public static void noteRenderEffectWrite(View view, String source, String detail) {
        if (!enabled || view == null) return;
        Entry prev = lastWrite.get(view);
        if (prev != null && prev.frameId == frameId && !prev.source.equals(source)) {
            Log.w(TAG, "RenderEffect conflict on " + describe(view)
                    + " - " + prev.source + "(" + prev.detail + ")"
                    + " then " + source + "(" + detail + ")"
                    + " both wrote frame=" + frameId);
        }
        lastWrite.put(view, new Entry(source, detail, frameId));
    }

    /** Logs when a tracked property changes by more than {@code threshold} in a single frame. */
    public static void noteValueJump(View view, String source, String prop,
                                     float oldVal, float newVal, float threshold) {
        if (!enabled || view == null) return;
        float delta = Math.abs(newVal - oldVal);
        if (delta > threshold) {
            Log.w(TAG, "Suspicious jump on " + describe(view) + " source=" + source
                    + " " + prop + " " + oldVal + " -> " + newVal
                    + " (delta=" + delta + ") frame=" + frameId);
        }
    }

    private static String describe(View view) {
        return view.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(view));
    }

    private static final class Entry {
        final String source;
        final String detail;
        final long frameId;

        Entry(String source, String detail, long frameId) {
            this.source = source;
            this.detail = detail;
            this.frameId = frameId;
        }
    }
}
