package com.eza.spicyex.lyrics;

import org.junit.Test;
import static org.junit.Assert.*;

public class SentenceVisualLineGeometryTest {
    @Test public void nestedTimingRowsStaySeparateFromTheFollowingOuterRow() {
        // Two internal rows of one timing group, followed by a normal flex row.
        float[][] words = {{0, 0, 100, 40}, {100, 0, 200, 40},
                {0, 40, 100, 80}, {100, 40, 200, 80}, {0, 80, 200, 120}};
        assertArrayEquals(new int[]{0, 0, 1, 1, 2}, SentenceFill.visualLineIndices(words));
        float gradient = LyricAnimations.gradientPosition(.1f);
        assertTrue(SentenceFill.lineGradient(gradient, 600, 0, 200) > LyricAnimations.GRADIENT_UNSUNG);
        assertEquals(LyricAnimations.GRADIENT_UNSUNG,
                SentenceFill.lineGradient(gradient, 600, 200, 200), .001f);
        assertEquals(LyricAnimations.GRADIENT_UNSUNG,
                SentenceFill.lineGradient(gradient, 600, 400, 200), .001f);
    }

    @Test public void drawingOrderDoesNotChangeVerticalReadingOrder() {
        float[][] words = {{0, 40, 100, 80}, {100, 2, 200, 40}, {0, 0, 100, 38}};
        assertArrayEquals(new int[]{1, 0, 0}, SentenceFill.visualLineIndices(words));
        assertArrayEquals(new int[0], SentenceFill.visualLineIndices(new float[0][]));
    }
}
