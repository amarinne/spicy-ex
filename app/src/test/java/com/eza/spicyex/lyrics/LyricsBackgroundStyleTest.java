package com.eza.spicyex.lyrics;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LyricsBackgroundStyleTest {
    @Test
    public void unknownValuesFallBackToGradient() {
        assertEquals(LyricsBackgroundStyle.GRADIENT, LyricsBackgroundStyle.normalize("old"));
        assertFalse(LyricsBackgroundStyle.usesTexture("old"));
    }

    @Test
    public void textureModesStayDistinct() {
        assertTrue(LyricsBackgroundStyle.usesTexture(LyricsBackgroundStyle.STATIC_TEXTURE));
        assertFalse(LyricsBackgroundStyle.isAnimated(LyricsBackgroundStyle.STATIC_TEXTURE));
        assertTrue(LyricsBackgroundStyle.usesTexture(LyricsBackgroundStyle.ANIMATED_TEXTURE));
        assertTrue(LyricsBackgroundStyle.isAnimated(LyricsBackgroundStyle.ANIMATED_TEXTURE));
    }
}
