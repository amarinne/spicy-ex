package com.eza.spicyex.ui;

import android.view.Choreographer;
import com.eza.spicyex.BuildConfig;
import java.util.Map;
import java.util.WeakHashMap;

public class VsyncFrameScheduler implements Choreographer.FrameCallback {
    public interface FrameListener {
        void onFrame(double deltaTimeSeconds);
    }

    private final Choreographer choreographer;
    private final FrameListener listener;

    private boolean running;
    private boolean continuous = true;
    private boolean framePosted;
    private long lastFrameNanos;
    private volatile boolean measuring;
    private FrameDeltaHistogram histogram;

    // Loaded only by debug start/stop or an explicitly requested debug measurement.
    private static final class DebugSchedulers {
        static final Map<VsyncFrameScheduler, Boolean> RUNNING = new WeakHashMap<>();
    }

    /** Main-thread diagnostic entry point. Reject ambiguity instead of mixing shell clocks. */
    public static VsyncFrameScheduler beginMeasurement(long periodNanos, long fromNanos, long untilNanos) {
        if (!BuildConfig.DEBUG) throw new IllegalStateException("debug build required");
        VsyncFrameScheduler scheduler;
        synchronized (DebugSchedulers.RUNNING) {
            if (DebugSchedulers.RUNNING.size() != 1) throw new IllegalStateException("expected one running scheduler");
            scheduler = DebugSchedulers.RUNNING.keySet().iterator().next();
        }
        if (scheduler.measuring) throw new IllegalStateException("measurement already running");
        scheduler.histogram = new FrameDeltaHistogram(periodNanos, fromNanos, untilNanos);
        scheduler.measuring = true;
        return scheduler;
    }

    /** Call on main after the timed window. The returned histogram is safe to read on any thread. */
    public FrameDeltaHistogram endMeasurement() {
        if (!BuildConfig.DEBUG) throw new IllegalStateException("debug build required");
        measuring = false;
        return histogram;
    }

    /**
     * Debug measurement only: follow a display-rate change so the histogram's 1.5x threshold
     * stays truthful for the rest of the window. No-op when no measurement is open.
     */
    public void updateMeasurementPeriod(long periodNanos) {
        if (!BuildConfig.DEBUG) throw new IllegalStateException("debug build required");
        if (!measuring || histogram == null) return;
        histogram.setPeriodNanos(periodNanos);
    }

    public VsyncFrameScheduler(FrameListener listener) {
        this(Choreographer.getInstance(), listener);
    }

    VsyncFrameScheduler(Choreographer choreographer, FrameListener listener) {
        this.choreographer = choreographer;
        this.listener = listener;
    }

    public void start() {
        if (running) {
            return;
        }

        running = true;
        if (BuildConfig.DEBUG) synchronized (DebugSchedulers.RUNNING) {
            for (VsyncFrameScheduler scheduler : DebugSchedulers.RUNNING.keySet()) {
                if (scheduler.measuring) scheduler.histogram.interrupt();
            }
            DebugSchedulers.RUNNING.put(this, Boolean.TRUE);
        }
        continuous = true;
        lastFrameNanos = 0L;
        postFrameIfNeeded();
    }

    public void stop() {
        if (!running) {
            return;
        }

        running = false;
        if (BuildConfig.DEBUG && measuring) histogram.interrupt();
        if (BuildConfig.DEBUG) synchronized (DebugSchedulers.RUNNING) {
            DebugSchedulers.RUNNING.remove(this);
        }
        framePosted = false;
        lastFrameNanos = 0L;
        choreographer.removeFrameCallback(this);
    }

    public boolean isRunning() {
        return running;
    }

    public boolean isContinuous() {
        return continuous;
    }

    public void setContinuous(boolean continuous) {
        if (this.continuous == continuous) return;
        this.continuous = continuous;
        if (BuildConfig.DEBUG && measuring) histogram.interrupt();
        if (continuous) {
            lastFrameNanos = 0L;
            postFrameIfNeeded();
        }
    }

    /**
     * Asks for one frame. Safe to call from anywhere, including from inside the frame callback.
     *
     * <p>The delta clock is only restarted when the loop was actually idle. It used to be zeroed
     * unconditionally, which quietly broke every animation driven off this scheduler: the lyric
     * shell calls this from its ScrollView scroll listener, and that listener fires on every frame
     * the scroll spring moves the view. Each of those calls zeroed {@code lastFrameNanos}, so the
     * next {@link #doFrame} reported a delta of 0 and the caller substituted a fixed 1/60s. Every
     * spring then advanced by 16.7ms of simulated time per real frame regardless of how long the
     * frame actually took - twice too fast on a 120Hz panel (which reads as a snap followed by a
     * twitch) and half speed on a phone dropping to 30fps (which reads as sluggish, stuttering
     * drift). While the loop is continuous the callback owns this field and it must not be reset.
     */
    public void requestFrame() {
        if (!running) return;
        if (!continuous && !framePosted) lastFrameNanos = 0L;
        postFrameIfNeeded();
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        framePosted = false;
        if (!running) {
            return;
        }

        if (BuildConfig.DEBUG && measuring) histogram.recordFrame(frameTimeNanos);
        double deltaTimeSeconds = 0.0d;
        if (lastFrameNanos != 0L) {
            deltaTimeSeconds = (frameTimeNanos - lastFrameNanos) / 1_000_000_000.0d;
        }

        lastFrameNanos = frameTimeNanos;
        listener.onFrame(deltaTimeSeconds);

        if (running && continuous) postFrameIfNeeded();
    }

    private void postFrameIfNeeded() {
        if (!running || framePosted) return;
        framePosted = true;
        choreographer.postFrameCallback(this);
    }
}
