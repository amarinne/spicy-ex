package com.eza.spicyex.lyrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AppleCompactCjkTest {
    @Test
    public void compactCurveRunsBelowShared() {
        assertEquals(19, LyricVisuals.appleLyricTextSizeSp("a".repeat(40)));
        assertEquals(20, LyricVisuals.appleLyricTextSizeSp("a".repeat(25)));
        assertEquals(21, LyricVisuals.appleLyricTextSizeSp("a".repeat(15)));
        assertEquals(23, LyricVisuals.appleLyricTextSizeSp("short"));
    }

    @Test
    public void letterAnimatorWidensOnlyUnderAppleFlag() {
        SyllableSegment seg = new SyllableSegment();
        seg.text = "hello world this is long";
        seg.totalMs = 500;
        assertFalse(LyricVisuals.shouldUseLetterAnimator(seg));
        assertTrue(LyricVisuals.shouldUseLetterAnimator(seg, true));
    }
}
