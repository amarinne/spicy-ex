package com.eza.spicyex.lyrics;

import org.junit.Test;
import static org.junit.Assert.*;

public class SentenceFillTest {
    @Test public void finishesFirstVisualLineBeforeStartingSecond() {
        float halfway = LyricAnimations.gradientPosition(.5f);
        assertEquals(LyricAnimations.GRADIENT_SUNG, SentenceFill.lineGradient(halfway, 300, 0, 100), .001f);
        assertEquals(LyricAnimations.gradientPosition(.25f), SentenceFill.lineGradient(halfway, 300, 100, 200), .001f);
        assertEquals(LyricAnimations.GRADIENT_UNSUNG,
                SentenceFill.lineGradient(LyricAnimations.gradientPosition(.2f), 300, 100, 200), .001f);
    }

    @Test public void clampsOvershootAndHandlesEmptyWidth() {
        assertEquals(LyricAnimations.GRADIENT_UNSUNG, SentenceFill.lineGradient(-500, 300, 0, 100), .001f);
        assertEquals(LyricAnimations.GRADIENT_SUNG, SentenceFill.lineGradient(500, 300, 100, 200), .001f);
        assertFalse(Float.isNaN(SentenceFill.lineGradient(0, 0, 0, 0)));
    }
}
