package com.eza.spicyex.hooks;

import android.os.SystemClock;

/** Tracks active lyric row and temporary manual-scroll hold state. */
final class LyricsFollowState {
    private int activeIndex = -2;
    private long holdUntilMs;
    private long lastManualScrollMs;
    private boolean touching;

    int activeIndex() {
        return activeIndex;
    }

    void setActiveIndex(int activeIndex) {
        this.activeIndex = activeIndex;
    }

    void resetActive() {
        activeIndex = -2;
    }

    void holdUntil(long untilMs) {
        holdUntilMs = untilMs;
    }

    void markManualScroll() {
        lastManualScrollMs = SystemClock.elapsedRealtime();
    }

    void setTouching(boolean touching) {
        this.touching = touching;
        if (touching) markManualScroll();
    }

    void clearHold() {
        holdUntilMs = 0;
    }

    boolean isHoldingNow() {
        return SystemClock.elapsedRealtime() < holdUntilMs;
    }

    boolean canAutoResumeNow(long cooldownMs) {
        return !touching && isHoldingNow() && SystemClock.elapsedRealtime() - lastManualScrollMs >= cooldownMs;
    }
}
