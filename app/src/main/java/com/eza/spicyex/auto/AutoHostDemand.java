package com.eza.spicyex.auto;

/** A live car host grants publication demand; a lost host expires without a keepalive. */
public final class AutoHostDemand {
    public static final long TIMEOUT_MS = 5000;
    private boolean connected;
    private long sampledAt = -1, notifiedAt = -1;

    /** Notify at connection edges and once per second while connected, including Spotify restarts. */
    public boolean update(boolean on, long now) {
        boolean changed = connected != on || (on && !active(now));
        connected = on;
        sampledAt = now;
        if (!changed && (!on || (notifiedAt >= 0 && now - notifiedAt < 1000))) return false;
        notifiedAt = now;
        return true;
    }

    public boolean active(long now) {
        return connected && sampledAt >= 0 && now >= sampledAt && now - sampledAt < TIMEOUT_MS;
    }
}
