package com.eza.spicyex.hooks;

/** Pure idempotent lifetime gate shared by session subscriptions, requests, and demand leases. */
final class LyricsSessionLifecycle {
    static final class HandleState {
        private boolean active = true;

        boolean isActive() {
            return active;
        }

        boolean close() {
            if (!active) return false;
            active = false;
            return true;
        }

        boolean consume() {
            return close();
        }
    }

    private LyricsSessionLifecycle() {}
}
