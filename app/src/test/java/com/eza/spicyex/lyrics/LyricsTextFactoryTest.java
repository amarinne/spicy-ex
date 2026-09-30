package com.eza.spicyex.lyrics;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LyricsTextFactoryTest {
    @Test
    public void indicFontFallbackPredicateDetectsIndicScripts() {
        assertTrue(LyricsTextFactory.shouldUseSystemFallbackForText("तुम ही हो"));
        assertTrue(LyricsTextFactory.shouldUseSystemFallbackForText("ਸਾਡਾ ਪਿਆਰ"));
        assertTrue(LyricsTextFactory.shouldUseSystemFallbackForText("ভালোবাসਾ"));
        assertFalse(LyricsTextFactory.shouldUseSystemFallbackForText("hello world"));
    }

    @Test
    public void mixedJapaneseFontFallbackTargetsOnlyCjkRuns() {
        java.util.List<int[]> ranges = LyricsTextFactory.cjkFontRanges("また今日 Hit my phone up");
        assertEquals(1, ranges.size());
        assertEquals(0, ranges.get(0)[0]);
        assertEquals(4, ranges.get(0)[1]);
    }

    @Test
    public void numericPersonUnitSharesOneCjkFontRun() {
        // 1人 must fold the ASCII digit into the CJK run so the ruby span [0,2] is drawn once.
        java.util.List<int[]> ranges = LyricsTextFactory.cjkFontRanges("1人で立ってるバス停");
        assertEquals(1, ranges.size());
        assertEquals(0, ranges.get(0)[0]);
        assertEquals(10, ranges.get(0)[1]);
    }

    @Test
    public void standaloneDigitsKeepLatinFont() {
        // "3年前" folds the digit (adjacent to CJK), but a lone Arabic numeral does not.
        assertEquals(1, LyricsTextFactory.cjkFontRanges("3年前").size());
        java.util.List<int[]> ranges = LyricsTextFactory.cjkFontRanges("track 2024 ready");
        assertTrue(ranges.isEmpty());
    }
}
