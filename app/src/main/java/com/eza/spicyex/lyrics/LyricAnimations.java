package com.eza.spicyex.lyrics;

import java.util.List;

/**
 * Pure animation curve math for the native Spicy renderer — ports of the Spicy 6 desktop
 * interpolation ranges plus the letter falloff/pulse helpers. No Android types, no state, so the
 * curve breakpoints (which are where karaoke-timing regressions hide) are unit-testable.
 *
 * Inputs are normalized progress in [0,1]; outputs are scale/offset/opacity/gradient multipliers.
 */
public final class LyricAnimations {
    public static final float GRADIENT_UNSUNG = -40f;
    public static final float GRADIENT_SUNG = 100f;
    public static final float GRADIENT_BAND = 40f;
    public static final float GRADIENT_RANGE = GRADIENT_SUNG - GRADIENT_UNSUNG;

    private LyricAnimations() {
    }

    public static float gradientPosition(float progress) {
        return GRADIENT_UNSUNG + GRADIENT_RANGE * clamp01(progress);
    }

    // --- word-level karaoke curves ---

    /** Spicy 6 ScaleRange: 0 -> 0.95, 0.7 -> 1.0505, 1 -> 1. */
    public static float scaleSpline(float t) {
        if (t <= 0.7f) return lerp(0.95f, 1.0505f, t / 0.7f);
        return lerp(1.0505f, 1.0f, (t - 0.7f) / 0.3f);
    }

    /** Spicy 6 YOffsetRange: 0 -> 0.01, 0.9 -> -1/60, 1 -> 0. */
    public static float yOffsetSpline(float t) {
        if (t <= 0.9f) return lerp(0.01f, -(1f / 60f), t / 0.9f);
        return lerp(-(1f / 60f), 0f, (t - 0.9f) / 0.1f);
    }

    /** Spicy 6 GlowRange: 0 -> 0, 0.15 -> 1, 0.6 -> 1, 1 -> 0. */
    public static float glowSpline(float t) {
        if (t <= 0.15f) return lerp(0f, 1f, t / 0.15f);
        if (t <= 0.6f) return 1f;
        return lerp(1f, 0f, (t - 0.6f) / 0.4f);
    }

    // --- letter-level karaoke curves (pop harder than words) ---

    /** Spicy 6 letter ScaleRange: 0 -> 0.95, 0.7 -> 1.18, 1 -> 1. */
    public static float letterScaleSpline(float t) {
        if (t <= 0.7f) return lerp(0.95f, 1.18f, t / 0.7f);
        return lerp(1.18f, 1.0f, (t - 0.7f) / 0.3f);
    }

    /** Spicy 6 letter YOffsetRange: 0 -> 0.01, 0.9 -> -1/50, 1 -> 0. */
    public static float letterYOffsetSpline(float t) {
        if (t <= 0.9f) return lerp(0.01f, -(1f / 50f), t / 0.9f);
        return lerp(-(1f / 50f), 0f, (t - 0.9f) / 0.1f);
    }

    /** d3 easeSinOut — desktop eases the active-letter gradient sweep with this. */
    public static float easeSinOut(float t) {
        return (float) Math.sin(clamp01(t) * (Math.PI / 2d));
    }

    /** Spatial falloff of the letter scale/offset around the active-letter anchor. */
    public static float letterMotionFalloff(float distance) {
        return (float) (1d / (1d + Math.pow(Math.max(0f, distance), 2.8d)));
    }

    /** Spatial falloff of the letter glow around the active-letter anchor. */
    public static float letterGlowFalloff(float distance) {
        return (float) (1d / (1d + Math.max(0f, distance) * 0.9d));
    }

    /** Fractional index of the active letter for a line at normalized time {@code timeAlpha}. */
    public static float activeLetterPosition(int letterCount, float timeAlpha) {
        if (letterCount <= 0) return 0f;
        float position = timeAlpha * letterCount;
        return Math.max(0f, Math.min(position, letterCount - 1f));
    }

    /** Convenience overload mirroring the renderer call site (uses only the list size). */
    public static float activeLetterPosition(List<?> letters, float timeAlpha) {
        return activeLetterPosition(letters == null ? 0 : letters.size(), timeAlpha);
    }

    // --- interlude dot curves ---

    public static float dotScaleSpline(float t) {
        if (t <= 0.7f) return lerp(0.75f, 1.05f, t / 0.7f);
        return lerp(1.05f, 1.0f, (t - 0.7f) / 0.3f);
    }

    public static float dotYOffsetSpline(float t) {
        if (t <= 0.9f) return lerp(0f, -0.12f, t / 0.9f);
        return lerp(-0.12f, 0f, (t - 0.9f) / 0.1f);
    }

    public static float dotGlowSpline(float t) {
        if (t <= 0.6f) return lerp(0f, 1f, t / 0.6f);
        return 1f;
    }

    public static float dotOpacitySpline(float t) {
        if (t <= 0.6f) return lerp(0.35f, 1f, t / 0.6f);
        return 1f;
    }

    // --- primitives (kept private so this class is the single source for the curve math) ---

    public static float lerp(float a, float b, float t) {
        return a + (b - a) * Math.max(0f, Math.min(1f, t));
    }

    public static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
