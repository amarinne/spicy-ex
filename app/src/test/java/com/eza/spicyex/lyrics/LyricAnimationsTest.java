package com.eza.spicyex.lyrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

/**
 * Locks the Spicy curve breakpoints in place. These piecewise interpolations are where
 * karaoke-timing regressions hide; the tests assert endpoints, the knot values, and continuity
 * across each breakpoint.
 */
public class LyricAnimationsTest {

    private static final float EPS = 1e-4f;

    private static void assertContinuous(java.util.function.Function<Float, Float> f, float breakpoint) {
        float before = f.apply(breakpoint - 1e-4f);
        float after = f.apply(breakpoint + 1e-4f);
        assertEquals("discontinuity at " + breakpoint, before, after, 2e-3f);
    }

    @Test
    public void scaleSplineKnots() {
        assertEquals(0.95f, LyricAnimations.scaleSpline(0f), EPS);
        assertEquals(1.0505f, LyricAnimations.scaleSpline(0.7f), EPS);
        assertEquals(1.0f, LyricAnimations.scaleSpline(1f), EPS);
        assertContinuous(LyricAnimations::scaleSpline, 0.7f);
    }

    @Test
    public void yOffsetSplineKnots() {
        assertEquals(0.01f, LyricAnimations.yOffsetSpline(0f), EPS);
        assertEquals(-(1f / 60f), LyricAnimations.yOffsetSpline(0.9f), EPS);
        assertEquals(0f, LyricAnimations.yOffsetSpline(1f), EPS);
        assertContinuous(LyricAnimations::yOffsetSpline, 0.9f);
    }

    @Test
    public void glowSplinePlateau() {
        assertEquals(0f, LyricAnimations.glowSpline(0f), EPS);
        assertEquals(1f, LyricAnimations.glowSpline(0.15f), EPS);
        assertEquals(1f, LyricAnimations.glowSpline(0.4f), EPS);
        assertEquals(1f, LyricAnimations.glowSpline(0.6f), EPS);
        assertEquals(0f, LyricAnimations.glowSpline(1f), EPS);
        assertContinuous(LyricAnimations::glowSpline, 0.15f);
        assertContinuous(LyricAnimations::glowSpline, 0.6f);
    }

    @Test
    public void letterSplinesPopHarderThanWords() {
        assertEquals(1.18f, LyricAnimations.letterScaleSpline(0.7f), EPS);
        assertTrue(LyricAnimations.letterScaleSpline(0.7f) > LyricAnimations.scaleSpline(0.7f));
        assertEquals(0.95f, LyricAnimations.letterScaleSpline(0f), EPS);
        assertEquals(1.0f, LyricAnimations.letterScaleSpline(1f), EPS);
        assertContinuous(LyricAnimations::letterScaleSpline, 0.7f);
        assertContinuous(LyricAnimations::letterYOffsetSpline, 0.9f);
    }

    @Test
    public void easeSinOutEndpoints() {
        assertEquals(0f, LyricAnimations.easeSinOut(0f), EPS);
        assertEquals(1f, LyricAnimations.easeSinOut(1f), EPS);
        assertEquals((float) Math.sin(Math.PI / 4d), LyricAnimations.easeSinOut(0.5f), EPS);
        // clamps out-of-range input
        assertEquals(1f, LyricAnimations.easeSinOut(2f), EPS);
        assertEquals(0f, LyricAnimations.easeSinOut(-1f), EPS);
    }

    @Test
    public void dotDetailSplinesMatchDesktopKnots() {
        assertEquals(0.75f, LyricAnimations.dotScaleSpline(0f), EPS);
        assertEquals(1.05f, LyricAnimations.dotScaleSpline(0.7f), EPS);
        assertEquals(1f, LyricAnimations.dotScaleSpline(1f), EPS);
        assertContinuous(LyricAnimations::dotScaleSpline, 0.7f);

        assertEquals(0f, LyricAnimations.dotYOffsetSpline(0f), EPS);
        assertEquals(-0.12f, LyricAnimations.dotYOffsetSpline(0.9f), EPS);
        assertEquals(0f, LyricAnimations.dotYOffsetSpline(1f), EPS);
        assertContinuous(LyricAnimations::dotYOffsetSpline, 0.9f);

        assertEquals(0f, LyricAnimations.dotGlowSpline(0f), EPS);
        assertEquals(1f, LyricAnimations.dotGlowSpline(0.6f), EPS);
        assertEquals(1f, LyricAnimations.dotGlowSpline(1f), EPS);
        assertContinuous(LyricAnimations::dotGlowSpline, 0.6f);

        assertEquals(0.35f, LyricAnimations.dotOpacitySpline(0f), EPS);
        assertEquals(1f, LyricAnimations.dotOpacitySpline(0.6f), EPS);
        assertEquals(1f, LyricAnimations.dotOpacitySpline(1f), EPS);
        assertContinuous(LyricAnimations::dotOpacitySpline, 0.6f);
    }

    @Test
    public void letterFalloffPeaksAtAnchor() {
        assertEquals(1f, LyricAnimations.letterMotionFalloff(0f), EPS);
        assertEquals(1f, LyricAnimations.letterGlowFalloff(0f), EPS);
        // strictly decreasing with distance
        assertTrue(LyricAnimations.letterMotionFalloff(1f) < LyricAnimations.letterMotionFalloff(0f));
        assertTrue(LyricAnimations.letterMotionFalloff(2f) < LyricAnimations.letterMotionFalloff(1f));
        assertTrue(LyricAnimations.letterGlowFalloff(3f) < LyricAnimations.letterGlowFalloff(1f));
        // negative distance is clamped to the peak
        assertEquals(1f, LyricAnimations.letterMotionFalloff(-5f), EPS);
    }

    @Test
    public void activeLetterPositionTracksTimeAndClamps() {
        assertEquals(0f, LyricAnimations.activeLetterPosition(5, 0f), EPS);
        assertEquals(2.5f, LyricAnimations.activeLetterPosition(5, 0.5f), EPS);
        assertEquals(4f, LyricAnimations.activeLetterPosition(5, 1f), EPS); // clamped to count-1
        assertEquals(0f, LyricAnimations.activeLetterPosition(0, 0.5f), EPS);
        assertEquals(LyricAnimations.activeLetterPosition(3, 0.5f),
                LyricAnimations.activeLetterPosition(Arrays.asList(1, 2, 3), 0.5f), EPS);
    }

    @Test
    public void dotTargetsUsePerDotIntervals() {
        SyllableSegment dot = new SyllableSegment();
        dot.startMs = 1000;
        dot.endMs = 2000;

        LyricsAnimationApplier.DotTargets notSung = LyricsAnimationApplier.dotTargets(dot, 999);
        assertEquals(0.75f, notSung.scale, EPS);
        assertEquals(0f, notSung.yOffset, EPS);
        assertEquals(0f, notSung.glow, EPS);
        assertEquals(0.35f, notSung.opacity, EPS);
        assertEquals(0f, notSung.gradientProgress, EPS);

        LyricsAnimationApplier.DotTargets active = LyricsAnimationApplier.dotTargets(dot, 1600);
        assertEquals(1f, active.glow, EPS);
        assertEquals(1f, active.opacity, EPS);
        assertEquals(0.6f, active.gradientProgress, EPS);

        LyricsAnimationApplier.DotTargets sung = LyricsAnimationApplier.dotTargets(dot, 2000);
        assertEquals(1f, sung.scale, EPS);
        assertEquals(0f, sung.yOffset, EPS);
        assertEquals(1f, sung.glow, EPS);
        assertEquals(1f, sung.opacity, EPS);
        assertEquals(1f, sung.gradientProgress, EPS);
    }

    @Test
    public void inactiveDotRowsFadeOutInsteadOfPersisting() {
        AppliedLine dots = new AppliedLine();
        dots.dotLine = true;

        assertEquals(0f, LyricsAnimationApplier.stepLineOpacity(dots, false, false, 1f / 60f), EPS);
    }

    @Test
    public void lerpClamps() {
        assertEquals(5f, LyricAnimations.lerp(0f, 10f, 0.5f), EPS);
        assertEquals(0f, LyricAnimations.lerp(0f, 10f, -1f), EPS);
        assertEquals(10f, LyricAnimations.lerp(0f, 10f, 2f), EPS);
        assertEquals(0f, LyricAnimations.clamp01(-0.5f), EPS);
        assertEquals(1f, LyricAnimations.clamp01(1.5f), EPS);
    }
}
