package com.eza.spicyex.lyrics;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** PR 15's line-scale ramp belongs to Apple Music style; other styles keep the flat targets. */
public class AppleOnlyMotionGateTest {
    private static final float EPS = 1e-4f;

    @Test
    public void nonAppleActiveLineKeepsFlatScaleTargets() {
        assertEquals(1.0f, target(3_000, false, false), EPS);
        assertEquals(1.04f, target(3_000, true, false), EPS);
        assertEquals(0.95f, target(500, false, false), EPS);
    }

    @Test
    public void appleActiveLineRampsTowardItsPeak() {
        float half = LyricAnimations.easeSinOut(0.5f);
        assertEquals(1.0f + 0.03f * half, target(3_000, false, true), EPS);
        assertEquals(1.0f + 0.05f * half, target(3_000, true, true), EPS);
    }

    private static float target(long positionMs, boolean spotlight, boolean appleStyle) {
        AppliedLine line = new AppliedLine();
        line.startMs = 1_000;
        line.endMs = 5_000;
        return LyricsLineAnimationState.forLine(line, positionMs, spotlight, true, false, appleStyle)
                .scaleTarget;
    }
}
