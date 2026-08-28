package com.eza.spicyex.lyrics;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class LyricsFrameRendererRouteTest {
    @Test
    public void blockUsesOneContentContainerWash() {
        assertEquals(LyricsFrameRenderer.WordGradientRoute.CONTINUOUS_BLOCK,
                LyricsFrameRenderer.wordGradientRoute("Left to right (block)"));
    }

    @Test
    public void sentenceUsesTimedWordAndSyllableSweep() {
        assertEquals(LyricsFrameRenderer.WordGradientRoute.TIMED_WORDS,
                LyricsFrameRenderer.wordGradientRoute("Left to right (sentence)"));
        assertEquals(LyricsFrameRenderer.WordGradientRoute.TIMED_WORDS,
                LyricsFrameRenderer.wordGradientRoute("Left to right (word)"));
    }

    @Test
    public void topDownStaysOnLineLevelRoute() {
        assertEquals(LyricsFrameRenderer.WordGradientRoute.LINE_LEVEL,
                LyricsFrameRenderer.wordGradientRoute("Top to bottom"));
    }
}
