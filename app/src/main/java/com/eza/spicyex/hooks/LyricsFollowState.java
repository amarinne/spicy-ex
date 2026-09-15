package com.eza.spicyex.hooks;

import java.util.function.LongSupplier;

/** Tracks active lyric row and temporary manual-scroll hold state. */
final class LyricsFollowState {
    private int activeIndex = -2;
    private long holdUntilMs;
    private boolean touching;
    private boolean manuallySuspended;
    private final LongSupplier clock;

    LyricsFollowState() {
        this(android.os.SystemClock::elapsedRealtime);
    }

    LyricsFollowState(LongSupplier clock) {
        this.clock = clock;
    }

    int activeIndex() {
        return activeIndex;
    }

    void setActiveIndex(int activeIndex) {
        this.activeIndex = activeIndex;
    }

    void resetActive() {
        activeIndex = -2;
        holdUntilMs = 0;
        touching = false;
        manuallySuspended = false;
    }

    void holdUntil(long untilMs) {
        holdUntilMs = untilMs;
    }

    void setTouching(boolean touching) {
        this.touching = touching;
        if (touching) manuallySuspended = true;
    }

    void clearHold() {
        holdUntilMs = 0;
        manuallySuspended = false;
    }

    boolean isHoldingNow() {
        return manuallySuspended || clock.getAsLong() < holdUntilMs;
    }

    boolean canAutoResumeNow() {
        return !touching && manuallySuspended && clock.getAsLong() >= holdUntilMs;
    }
}
