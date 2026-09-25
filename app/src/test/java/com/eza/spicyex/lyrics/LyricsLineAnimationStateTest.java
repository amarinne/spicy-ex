package com.eza.spicyex.lyrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LyricsLineAnimationStateTest {
    private static final float EPS = 1e-4f;

    @Test
    public void gradientWashTracksLineProgress() {
        AppliedLine line = line(1_000, 5_000);

        LyricsLineAnimationState state = LyricsLineAnimationState.forLine(line, 3_000, false, true);

        assertTrue(state.active);
        assertFalse(state.sung);
        assertEquals(0.5f, state.progress, EPS);
        assertEquals(30f, state.gradient, EPS);
        assertEquals(0.33f, state.glowTarget, EPS);
        assertEquals(1.0f, state.brightnessTarget, EPS);
        float expectedScale = 1.0f + 0.03f * LyricAnimations.easeSinOut(0.5f);
        assertEquals(expectedScale, state.scaleTarget, EPS);
    }

    @Test
    public void spotlightLightsWholeActiveLine() {
        AppliedLine line = line(1_000, 5_000);

        LyricsLineAnimationState state = LyricsLineAnimationState.forLine(line, 3_000, true, true);

        assertEquals(100f, state.gradient, EPS);
        assertEquals(0.5f, state.glowTarget, EPS);
        assertEquals(0.71f, state.brightnessTarget, EPS);
        float expectedSpotlightScale = 1.0f + 0.05f * LyricAnimations.easeSinOut(0.5f);
        assertEquals(expectedSpotlightScale, state.scaleTarget, EPS);
    }

    @Test
    public void disabledWashKeepsLineFlatForLiveCard() {
        AppliedLine line = line(1_000, 5_000);

        LyricsLineAnimationState state = LyricsLineAnimationState.forLine(line, 3_000, false, false);

        assertEquals(100f, state.gradient, EPS);
        assertEquals(0f, state.glowTarget, EPS);
        assertEquals(1f, state.brightnessTarget, EPS);
    }

    @Test
    public void inactiveAndSungEndpointsMatchRendererStates() {
        // forLine() reuses one mutable instance per line, so each call's fields must be read
        // before the next call for the same line overwrites them.
        AppliedLine before = line(1_000, 5_000);
        LyricsLineAnimationState beforeState = LyricsLineAnimationState.forLine(before, 500, false, true);
        assertFalse(beforeState.active);
        assertFalse(beforeState.sung);
        assertEquals(LyricAnimations.GRADIENT_UNSUNG, beforeState.gradient, EPS);
        assertEquals(0.95f, beforeState.scaleTarget, EPS);

        AppliedLine after = line(1_000, 5_000);
        LyricsLineAnimationState afterState = LyricsLineAnimationState.forLine(after, 5_500, false, true);
        assertFalse(afterState.active);
        assertTrue(afterState.sung);
        assertEquals(100f, afterState.gradient, EPS);
        assertEquals(0.5f, afterState.glowTarget, EPS);
        assertEquals(0.95f, afterState.scaleTarget, EPS);
    }

    @Test
    public void spotlightRetainsPeakGlowAfterLineFinishes() {
        AppliedLine line = line(1_000, 5_000);

        LyricsLineAnimationState after = LyricsLineAnimationState.forLine(line, 5_500, true, true);

        assertTrue(after.sung);
        assertEquals(1f, after.glowTarget, EPS);
    }

    private static AppliedLine line(long startMs, long endMs) {
        AppliedLine line = new AppliedLine();
        line.startMs = startMs;
        line.endMs = endMs;
        return line;
    }
}
