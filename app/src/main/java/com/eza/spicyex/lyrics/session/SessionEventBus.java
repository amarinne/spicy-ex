package com.eza.spicyex.lyrics.session;

import java.util.ArrayList;
import java.util.List;

/**
 * Fan-out of {@link SessionEvent}s to every render surface from one session.
 *
 * <p>Fullscreen, now-playing, and HyperGlow are different subscribers of the same events, never
 * different processing owners: subscribing does not start work, and one publication reaches all
 * of them. Dispatch order is registration order and a subscriber added mid-dispatch is not called
 * for the in-progress event.
 */
public final class SessionEventBus {
    public interface Subscriber {
        void onSessionEvent(SessionEvent event);
    }

    public interface Subscription extends AutoCloseable {
        @Override void close();
    }

    private final List<Subscriber> subscribers = new ArrayList<>();
    private SessionEvent last;

    /** Registers {@code subscriber} and replays the last event so a late surface starts current. */
    public Subscription subscribe(Subscriber subscriber) {
        if (subscriber == null) return () -> { };
        subscribers.add(subscriber);
        if (last != null) dispatchTo(subscriber, last);
        return new Handle(subscriber);
    }

    public void publish(SessionEvent event) {
        if (event == null) return;
        last = event;
        for (Subscriber subscriber : new ArrayList<>(subscribers)) dispatchTo(subscriber, event);
    }

    public SessionEvent last() {
        return last;
    }

    public int subscriberCount() {
        return subscribers.size();
    }

    /** Drops the replay buffer, e.g. when the session's track changes. */
    public void reset() {
        last = null;
    }

    private static void dispatchTo(Subscriber subscriber, SessionEvent event) {
        try {
            subscriber.onSessionEvent(event);
        } catch (Throwable ignored) {
            // One misbehaving surface must not stop publication to the others.
        }
    }

    private final class Handle implements Subscription {
        private final Subscriber subscriber;
        private boolean closed;

        Handle(Subscriber subscriber) {
            this.subscriber = subscriber;
        }

        @Override public void close() {
            if (closed) return;
            closed = true;
            subscribers.remove(subscriber);
        }
    }
}
