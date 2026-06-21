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
        assertEquals(40f, state.gradient, EPS);
        assertEquals(0.33f, state.glowTarget, EPS);
        assertEquals(1.0f, state.brightnessTarget, EPS);
        assertEquals(1.0f, state.scaleTarget, EPS);
    }

    @Test
    public void spotlightLightsWholeActiveLine() {
        AppliedLine line = line(1_000, 5_000);

        LyricsLineAnimationState state = LyricsLineAnimationState.forLine(line, 3_000, true, true);

        assertEquals(100f, state.gradient, EPS);
        assertEquals(0.5f, state.glowTarget, EPS);
        assertEquals(0.71f, state.brightnessTarget, EPS);
        assertEquals(1.04f, state.scaleTarget, EPS);
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
        AppliedLine line = line(1_000, 5_000);

        LyricsLineAnimationState before = LyricsLineAnimationState.forLine(line, 500, false, true);
        LyricsLineAnimationState after = LyricsLineAnimationState.forLine(line, 5_500, false, true);

        assertFalse(before.active);
        assertFalse(before.sung);
        assertEquals(-20f, before.gradient, EPS);
        assertEquals(0.95f, before.scaleTarget, EPS);

        assertFalse(after.active);
        assertTrue(after.sung);
        assertEquals(100f, after.gradient, EPS);
        assertEquals(0.95f, after.scaleTarget, EPS);
    }

    private static AppliedLine line(long startMs, long endMs) {
        AppliedLine line = new AppliedLine();
        line.startMs = startMs;
        line.endMs = endMs;
        return line;
    }
}
