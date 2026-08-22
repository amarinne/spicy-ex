package com.eza.spicyex.lyrics;

/** Pure geometry for live-card single-line overflow. */
final class LyricsOverflowGeometry {
    private LyricsOverflowGeometry() {
    }

    static int childLeft(int viewportWidth, int contentWidth, boolean oppositeAligned, boolean rtl) {
        if (contentWidth > viewportWidth) return rtl ? viewportWidth - contentWidth : 0;
        boolean alignRight = rtl ? !oppositeAligned : oppositeAligned;
        return alignRight ? Math.max(0, viewportWidth - contentWidth) : 0;
    }

    static float target(float maxScroll, float progress, boolean rtl) {
        float boundedProgress = Math.max(0f, Math.min(1f, progress));
        float distance = Math.max(0f, maxScroll) * boundedProgress;
        return rtl ? distance : -distance;
    }

    static float clampTarget(float target, float maxScroll, boolean rtl) {
        float boundedMax = Math.max(0f, maxScroll);
        return rtl
                ? Math.max(0f, Math.min(boundedMax, target))
                : Math.max(-boundedMax, Math.min(0f, target));
    }
}
