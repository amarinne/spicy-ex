package com.eza.spicyex.auto;

/** Fail-closed rules shared by the producer, transport, and rendering adapter. */
public final class AutoPrototypePolicy {
    public static final long MAX_AGE_MS = 8000;
    public static final long PAUSE_RELEASE_MS = 3000;
    private AutoPrototypePolicy() { }
    public static boolean suppressFullProgress(boolean takeover, boolean spotify, float contentHeightDp) {
        return takeover && spotify && contentHeightDp > 0 && contentHeightDp <= 400;
    }
    public static boolean fullPlayerTakeover(boolean ready, boolean hasSyncedLyrics, boolean stress, boolean nativeWithoutLyrics) {
        return ready && (hasSyncedLyrics || stress || !nativeWithoutLyrics);
    }
    public static boolean suppressCompactThumbnail(boolean playerReady, boolean spotify, boolean weatherVisible) {
        return playerReady && spotify && !weatherVisible;
    }

    public static boolean suppressCompactBadge(boolean playerReady, boolean spotify, boolean weatherVisible) {
        return playerReady && spotify && !weatherVisible;
    }
    public static boolean fresh(long sampledAt, long now) {
        return sampledAt > 0 && sampledAt <= now && now - sampledAt < MAX_AGE_MS;
    }
    public static boolean sameSession(int currentGeneration, String currentUri, int generation, String uri) {
        return currentGeneration == generation && currentUri != null && !currentUri.isEmpty() && currentUri.equals(uri);
    }
    public static long position(long sampledPosition, long sampledAt, boolean playing, long now) {
        return position(sampledPosition, sampledAt, playing, 1d, now);
    }
    /** Matches the shared lyrics clock's bounded rate; zero means buffering, not paused. */
    public static double playbackRate(double rate) {
        return Double.isNaN(rate) || rate < 0d || rate > 8d ? 1d : rate;
    }
    public static long position(long sampledPosition, long sampledAt, boolean playing, double rate, long now) {
        return sampledPosition + (playing ? Math.round(Math.max(0, now - sampledAt) * playbackRate(rate)) : 0);
    }

    /** Holds the native surface through lyric gaps and brief pauses, independent of row timing. */
    public static final class PlaybackHold {
        private boolean active;
        private long pausedAt = -1;
        public boolean update(boolean valid, boolean playing, long now) {
            return update(valid, playing, now, PAUSE_RELEASE_MS);
        }
        public boolean update(boolean valid, boolean playing, long now, long pauseHoldMs) {
            if (!valid) { active = false; pausedAt = -1; }
            else if (playing) { active = true; pausedAt = -1; }
            else if (active) {
                if (pausedAt < 0) pausedAt = now;
                if (now - pausedAt >= pauseHoldMs) active = false;
            }
            return active;
        }
    }
}
