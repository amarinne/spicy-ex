package com.eza.spicyex.lyrics;

/** Shared line-level animation target for fullscreen rows, synthetic word rows, and live card. */
public final class LyricsLineAnimationState {
    public final boolean active;
    public final boolean sung;
    public final boolean spotlight;
    public final float progress;
    public final float gradient;
    public final float glowTarget;
    public final float scaleTarget;

    private LyricsLineAnimationState(
            boolean active,
            boolean sung,
            boolean spotlight,
            float progress,
            float gradient,
            float glowTarget,
            float scaleTarget
    ) {
        this.active = active;
        this.sung = sung;
        this.spotlight = spotlight;
        this.progress = progress;
        this.gradient = gradient;
        this.glowTarget = glowTarget;
        this.scaleTarget = scaleTarget;
    }

    public static LyricsLineAnimationState forLine(
            AppliedLine line,
            long positionMs,
            boolean spotlight,
            boolean washEnabled
    ) {
        boolean active = LyricTimeline.isRowActiveAt(line, positionMs);
        boolean sung = line != null && positionMs >= line.endMs;
        float progress = active ? progress01(positionMs, line.startMs, LyricTimeline.fillEndMs(line)) : 0f;
        float gradient;
        if (spotlight) {
            gradient = active || sung ? 100f : -20f;
        } else if (!washEnabled) {
            gradient = 100f;
        } else if (active) {
            gradient = -20f + 120f * progress;
        } else {
            gradient = sung ? 100f : -20f;
        }

        float glowTarget = 0f;
        if (active && (spotlight || washEnabled)) {
            float glowPeak = spotlight ? 1.0f : 0.5f;
            glowTarget = 0.16f + (glowPeak - 0.16f) * progress;
        }
        float scaleTarget = active ? (spotlight ? 1.04f : 1.0f) : 0.95f;
        return new LyricsLineAnimationState(active, sung, spotlight, progress, gradient, glowTarget, scaleTarget);
    }

    private static float progress01(long positionMs, long startMs, long endMs) {
        if (endMs <= startMs) return positionMs >= endMs ? 1f : 0f;
        return LyricAnimations.clamp01((float) (positionMs - startMs) / (float) (endMs - startMs));
    }
}
