package com.eza.spicyex.beautifullyrics.entities;

import android.view.Choreographer;

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
        continuous = true;
        lastFrameNanos = 0L;
        postFrameIfNeeded();
    }

    public void stop() {
        if (!running) {
            return;
        }

        running = false;
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
