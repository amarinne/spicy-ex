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

    public void requestFrame() {
        if (!running) return;
        lastFrameNanos = 0L;
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
