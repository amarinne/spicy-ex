package com.eza.spicyex.lyrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AppleLiftMotionTest {
    private static final float EPS = 0.0001f;

    @Test
    public void appleLiftSplineRisesAndHolds() {
        assertEquals(0f, LyricAnimations.appleLiftYOffsetSpline(0f), EPS);
        assertEquals(-0.04f, LyricAnimations.appleLiftYOffsetSpline(1f), EPS);
        float mid = LyricAnimations.appleLiftYOffsetSpline(0.5f);
        assertTrue(mid < 0f && mid > -0.04f);
    }

    @Test
    public void wordMotionYPicksSplineByFlag() {
        // Apple lift holds -0.04 once sung; the shared lift arc returns to 0.
        assertEquals(-0.04f,
                LyricsAnimationApplier.wordMotionY(true, false, true, 0.9f, true), EPS);
        assertEquals(0f,
                LyricsAnimationApplier.wordMotionY(true, false, true, 0.9f, false), EPS);
        // Inactive and unsung words never move under either curve.
        assertEquals(0f,
                LyricsAnimationApplier.wordMotionY(true, false, false, 0.5f, true), EPS);
        // Without the flag the legacy 4-arg path is unchanged.
        assertEquals(LyricAnimations.liftYOffsetSpline(0.5f),
                LyricsAnimationApplier.wordMotionY(true, true, false, 0.5f), EPS);
    }

    @Test
    public void appleGlowFalloffsBracketShared() {
        // Wider wash and tighter anchor than the shared falloff at distance 2.
        assertTrue(LyricAnimations.appleLetterGlowFalloff(2f)
                > LyricAnimations.letterGlowFalloff(2f));
        assertTrue(LyricAnimations.appleActiveLetterGlowFalloff(2f)
                > LyricAnimations.appleLetterGlowFalloff(2f));
    }

    @Test
    public void sharedAppleLiftStyleSelectsMotionWithoutAppleStyle() {
        assertTrue(LyricsRenderConfig.appleLiftMotion("Apple lift", false, false));
        assertTrue(LyricsRenderConfig.appleLiftMotion("Apple lift", true, false));
    }

    @Test
    public void appleToggleStillGatesOnAppleStyle() {
        assertTrue(LyricsRenderConfig.appleLiftMotion("Word lift", true, true));
        assertFalse(LyricsRenderConfig.appleLiftMotion("Word lift", false, true));
        assertFalse(LyricsRenderConfig.appleLiftMotion("Phrase zoom", false, false));
    }
}
