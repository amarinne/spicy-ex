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

    // --- held-note emphasis (Apple "swell") ---------------------------------
    //
    // Apple Music grows a word while it is being held, and how much it grows depends on how long
    // it is held relative to the rest of its own line - so a ballad's sustained note swells while
    // a rap verse, whose words are all short, stays flat. Judging each line against its own
    // average is what keeps both readable; an absolute duration threshold would emphasise every
    // word of a slow song and none of a fast one.
    //
    // The previous curve here (slowWordDistortion) had two problems this replaces. It applied one
    // fixed amplitude no matter how long the note actually was, so a half-second word and a
    // four-second word swelled identically; and it *dipped below* its resting size at 70% before
    // recovering, which reads as a wobble rather than a note being held. A held note rises, stays
    // risen for as long as it is sung, and settles as it ends.

    /** Peak growth, as a fraction of resting size, at full strength. */
    public static final float EMPHASIS_MAX_SCALE = 0.18f;
    /** Peak float-up, in em, at full strength. */
    public static final float EMPHASIS_MAX_LIFT_EM = 0.03f;
    /** Duration ratio against the line average at which a word starts to swell at all. */
    public static final float EMPHASIS_MIN_RATIO = 1.6f;
    /** Duration ratio against the line average at which the swell reaches full strength. */
    public static final float EMPHASIS_FULL_RATIO = 4.0f;
    /** A word held for less than this never swells, however short its line's average is. */
    public static final long EMPHASIS_MIN_HOLD_MS = 600L;
    private static final float EMPHASIS_RISE = 0.28f;
    private static final float EMPHASIS_RELEASE = 0.80f;

    /**
     * How strongly a word should swell, in [0,1], from its own duration against its line's average
     * word duration. Zero for anything at or below {@link #EMPHASIS_MIN_RATIO}, or shorter than
     * {@link #EMPHASIS_MIN_HOLD_MS} in absolute terms.
     */
    public static float emphasisStrength(long wordMs, long lineAverageMs) {
        if (wordMs < EMPHASIS_MIN_HOLD_MS || lineAverageMs <= 0) return 0f;
        float ratio = wordMs / (float) lineAverageMs;
        if (ratio <= EMPHASIS_MIN_RATIO) return 0f;
        return clamp01((ratio - EMPHASIS_MIN_RATIO) / (EMPHASIS_FULL_RATIO - EMPHASIS_MIN_RATIO));
    }

    /** Rise, hold, release. Returns a scale multiplier around 1. */
    public static float emphasisScale(float t, float strength) {
        return 1f + EMPHASIS_MAX_SCALE * clamp01(strength) * emphasisEnvelope(t);
    }

    /** Vertical companion to {@link #emphasisScale}, in em (negative is up). */
    public static float emphasisLiftEm(float t, float strength) {
        return -EMPHASIS_MAX_LIFT_EM * clamp01(strength) * emphasisEnvelope(t);
    }

    /** 0 -> 1 over the opening, held at 1 through the sustain, back to 0 as the note ends. */
    public static float emphasisEnvelope(float t) {
        float p = clamp01(t);
        if (p < EMPHASIS_RISE) return smoothStep(p / EMPHASIS_RISE);
        if (p < EMPHASIS_RELEASE) return 1f;
        return 1f - smoothStep((p - EMPHASIS_RELEASE) / (1f - EMPHASIS_RELEASE));
    }

    /** Zoom style: recessed rest -> gentle peak -> neutral sung state. */
    public static float scaleSpline(float t) {
        if (t <= 0.7f) return lerp(0.95f, 1.025f, smoothStep(t / 0.7f));
        return lerp(1.025f, 1f, smoothStep((t - 0.7f) / 0.3f));
    }

    /** Word-granularity version of {@link #letterScaleSpline}'s amplitude (0.95 -> 1.18 -> 1.0),
     *  for words that qualify for the strong pop but can't run the per-letter animator - e.g. a
     *  furigana-annotated Japanese word, whose ruby span needs a single contiguous text layout
     *  rather than one view per code point. Keeps grow intensity comparable to English's
     *  per-letter pop instead of falling back to the much weaker {@link #scaleSpline}. */
    public static float wordScaleSplineStrong(float t) {
        if (t <= 0.7f) return lerp(0.95f, 1.18f, smoothStep(t / 0.7f));
        return lerp(1.18f, 1f, smoothStep((t - 0.7f) / 0.3f));
    }

    /** Zoom style Y range retained from the original Spicy curve. */
    public static float yOffsetSpline(float t) {
        if (t <= 0.9f) return lerp(0.01f, -(1f / 60f), t / 0.9f);
        return lerp(-(1f / 60f), 0f, (t - 0.9f) / 0.1f);
    }

    /** Lift style: smooth vertical-only arc, 0 -> -0.04em -> 0. */
    public static float liftYOffsetSpline(float t) {
        float progress = clamp01(t);
        float wave = (float) Math.sin(Math.PI * progress);
        return -0.04f * wave * wave;
    }

    /** Apple lift height. 0.04em (~3px at lyric size) was too small to read as a lift at all. */
    public static final float APPLE_LIFT_EM = 0.07f;

    /** Apple lift: quarter-sine rise to -{@link #APPLE_LIFT_EM}, holding at the top while sung. */
    public static float appleLiftYOffsetSpline(float t) {
        return -APPLE_LIFT_EM * (float) Math.sin(clamp01(t) * (Math.PI / 2d));
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

    /** Apple letter-glow falloffs: wider wash, tighter active anchor. */
    public static float appleLetterGlowFalloff(float distance) {
        return (float) (1d / (1d + Math.max(0f, distance) * 0.5d));
    }

    public static float appleActiveLetterGlowFalloff(float distance) {
        return (float) (1d / (1d + Math.max(0f, distance) * 0.22d));
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

    private static float smoothStep(float value) {
        float t = clamp01(value);
        return t * t * (3f - 2f * t);
    }

    public static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
