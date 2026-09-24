package com.eza.spicyex.lyrics;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Shared line-level animation target for fullscreen rows, synthetic word rows, and live card.
 *
 * <p>The instance returned by {@link #forLine} is reused per {@link AppliedLine} and mutated in
 * place on each call (keyed weakly so entries die with the line) instead of allocating a new
 * object every Choreographer frame. Callers must read the fields they need before the next
 * {@code forLine} call for the same line — don't hold two results for the same line to compare
 * "before" and "after", since both references point at the same mutated instance.
 */
public final class LyricsLineAnimationState {
    private static final Map<AppliedLine, LyricsLineAnimationState> STATES = new WeakHashMap<>();

    public boolean active;
    public boolean sung;
    public boolean spotlight;
    public float progress;
    public float gradient;
    public float glowTarget;
    public float brightnessTarget;
    public float scaleTarget;

    private LyricsLineAnimationState() {
    }

    private void set(
            boolean active,
            boolean sung,
            boolean spotlight,
            float progress,
            float gradient,
            float glowTarget,
            float brightnessTarget,
            float scaleTarget
    ) {
        this.active = active;
        this.sung = sung;
        this.spotlight = spotlight;
        this.progress = progress;
        this.gradient = gradient;
        this.glowTarget = glowTarget;
        this.brightnessTarget = brightnessTarget;
        this.scaleTarget = scaleTarget;
    }

    public static LyricsLineAnimationState forLine(
            AppliedLine line,
            long positionMs,
            boolean spotlight,
            boolean washEnabled
    ) {
        return forLine(line, positionMs, spotlight, washEnabled, false);
    }

    public static LyricsLineAnimationState forLine(
            AppliedLine line,
            long positionMs,
            boolean spotlight,
            boolean washEnabled,
            boolean appleDimPassed
    ) {
        boolean active = LyricTimeline.isRowActiveAt(line, positionMs);
        boolean sung = line != null && positionMs >= line.endMs;
        float progress = active ? progress01(positionMs, line.startMs, LyricTimeline.fillEndMs(line)) : 0f;
        float gradient;
        if (spotlight) {
            gradient = active || sung ? LyricAnimations.GRADIENT_SUNG : LyricAnimations.GRADIENT_UNSUNG;
        } else if (!washEnabled) {
            gradient = LyricAnimations.GRADIENT_SUNG;
        } else if (active) {
            gradient = LyricAnimations.gradientPosition(progress);
        } else {
            gradient = sung ? LyricAnimations.GRADIENT_SUNG : LyricAnimations.GRADIENT_UNSUNG;
        }

        float glowTarget = 0f;
        if ((active || sung) && (spotlight || washEnabled)) {
            float glowPeak = spotlight ? 1.0f : 0.5f;
            if (sung) {
                glowTarget = appleDimPassed ? glowPeak * 0.3f : glowPeak;
            } else {
                glowTarget = spotlight
                        ? glowPeak * LyricAnimations.easeSinOut(progress) * LyricAnimations.easeSinOut(progress)
                        : 0.16f + (glowPeak - 0.16f) * progress;
            }
        }
        float brightnessTarget = 1f;
        if (spotlight && active) {
            float eased = LyricAnimations.easeSinOut(progress);
            brightnessTarget = 0.42f + 0.58f * eased * eased;
        }
        float scaleTarget = active ? (spotlight ? 1.04f : 1.0f) : 0.95f;
        LyricsLineAnimationState state = line == null ? new LyricsLineAnimationState() : state(line);
        state.set(active, sung, spotlight, progress, gradient, glowTarget, brightnessTarget, scaleTarget);
        return state;
    }

    private static LyricsLineAnimationState state(AppliedLine line) {
        LyricsLineAnimationState state = STATES.get(line);
        if (state == null) {
            state = new LyricsLineAnimationState();
            STATES.put(line, state);
        }
        return state;
    }

    private static float progress01(long positionMs, long startMs, long endMs) {
        if (endMs <= startMs) return positionMs >= endMs ? 1f : 0f;
        return LyricAnimations.clamp01((float) (positionMs - startMs) / (float) (endMs - startMs));
    }
}
