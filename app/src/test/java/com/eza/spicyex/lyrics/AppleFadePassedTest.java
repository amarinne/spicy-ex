package com.eza.spicyex.lyrics;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class AppleFadePassedTest {
    private static AppliedLine line(long startMs, long endMs) {
        AppliedLine row = new AppliedLine();
        row.startMs = startMs;
        row.endMs = endMs;
        row.totalMs = Math.max(1, endMs - startMs);
        return row;
    }

    private static float settledOpacity(boolean active, boolean sung, boolean appleDimPassed) {
        AppliedLine row = line(10000, 15000);
        float value = 1f;
        for (int i = 0; i < 600; i++) {
            value = LyricsAnimationApplier.stepLineOpacity(row, active, sung, 1f / 60f, appleDimPassed);
        }
        return value;
    }

    @Test
    public void dimTargetsOnlyUnderAppleFlag() {
        // Shared targets unchanged.
        assertEquals(0.82f, settledOpacity(false, true, false), 0.01f);
        assertEquals(0.42f, settledOpacity(false, false, false), 0.01f);
        // Apple fade-passed targets.
        assertEquals(0.60f, settledOpacity(false, true, true), 0.01f);
        assertEquals(0.38f, settledOpacity(false, false, true), 0.01f);
        // Active lines stay full under both.
        assertEquals(1f, settledOpacity(true, false, true), 0.01f);
    }

    @Test
    public void forLineDimsSungGlowOnlyUnderAppleFlag() {
        // forLine() reuses one mutable instance per line, so each line needs its own
        // AppliedLine to hold two results (shared vs. dimmed) at once for comparison.
        float sharedGlow = LyricsLineAnimationState.forLine(line(10000, 15000), 20000, false, true, false).glowTarget;
        float dimmedGlow = LyricsLineAnimationState.forLine(line(10000, 15000), 20000, false, true, true).glowTarget;
        assertEquals(sharedGlow * 0.3f, dimmedGlow, 0.001f);
    }
}
