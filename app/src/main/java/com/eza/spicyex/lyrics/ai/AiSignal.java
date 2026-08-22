package com.eza.spicyex.lyrics.ai;

import java.util.ArrayList;
import java.util.List;

/**
 * Cancellation for one run, and the child signals its calls hang off.
 *
 * <p>What it can and cannot promise is worth being exact about. Aborting stops us waiting and lets
 * the transport drop the connection, so it saves work. It does not prove the provider stopped
 * before billing — the request may already have been served. Nothing built on this may tell the
 * owner a cancelled call definitely cost nothing.
 */
public final class AiSignal {
    /** Reason token, never lyric text: {@code track_change}, {@code timeout}, {@code user}, …. */
    private volatile String reason;
    private final List<Runnable> listeners = new ArrayList<>(2);

    public boolean isAborted() {
        return reason != null;
    }

    public String reason() {
        return AiText.nz(reason);
    }

    public void abort(String reason) {
        List<Runnable> pending;
        synchronized (this) {
            if (this.reason != null) return;
            this.reason = AiText.nz(reason).isEmpty() ? "cancelled" : reason;
            pending = new ArrayList<>(listeners);
            listeners.clear();
            notifyAll();
        }
        for (Runnable listener : pending) {
            try {
                listener.run();
            } catch (Throwable ignored) {
                // A listener that throws must not stop the rest from being told.
            }
        }
    }

    public void throwIfAborted() {
        if (isAborted()) throw new AiCancelledException(reason());
    }

    /**
     * Runs {@code listener} once, when this signal aborts — immediately if it already has.
     *
     * @return a handle to drop the listener when the work it guards is over, so a long-lived run
     *         signal does not accumulate one registration per call it made
     */
    public Registration onAbort(final Runnable listener) {
        synchronized (this) {
            if (reason == null) {
                listeners.add(listener);
                return new Registration(this, listener);
            }
        }
        listener.run();
        return new Registration(this, listener);
    }

    /** Waits up to {@code timeoutMs}. @return true if the signal aborted within that time */
    public synchronized boolean awaitAbort(long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + Math.max(0L, timeoutMs);
        while (reason == null) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0L) return false;
            wait(remaining);
        }
        return true;
    }

    /** A child that aborts when its parent does, and can be aborted alone for a call deadline. */
    public static AiSignal child(AiSignal parent) {
        final AiSignal child = new AiSignal();
        if (parent != null) parent.onAbort(new Runnable() {
            @Override public void run() {
                child.abort(parent.reason());
            }
        });
        return child;
    }

    /** Links {@code parent} to {@code child} and returns the handle that unlinks them. */
    public static Registration link(AiSignal parent, final AiSignal child) {
        if (parent == null) return Registration.NONE;
        final AiSignal source = parent;
        return parent.onAbort(new Runnable() {
            @Override public void run() {
                child.abort(source.reason());
            }
        });
    }

    /** Undoes one {@link #onAbort} registration. */
    public static final class Registration {
        static final Registration NONE = new Registration(null, null);

        private final AiSignal signal;
        private final Runnable listener;

        Registration(AiSignal signal, Runnable listener) {
            this.signal = signal;
            this.listener = listener;
        }

        public void remove() {
            if (signal == null) return;
            synchronized (signal) {
                signal.listeners.remove(listener);
            }
        }
    }
}
