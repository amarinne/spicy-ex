package com.eza.spicyex.lyrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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

    @Test
    public void degenerateWordTimingFlagsCompressedSpans() {
        AppliedLine line = new AppliedLine();
        line.startMs = 1000;
        line.endMs = 5000;
        line.words.add(segment("a", 1000, 1100));
        line.words.add(segment("b", 1100, 1150));
        line.words.add(segment("c", 1150, 1200));
        assertTrue(LyricsFrameRenderer.hasDegenerateWordTiming(line));
    }

    @Test
    public void degenerateWordTimingFlagsZeroDurationWords() {
        AppliedLine line = new AppliedLine();
        line.startMs = 1000;
        line.endMs = 3000;
        line.words.add(segment("a", 1000, 1500));
        line.words.add(segment("b", 2000, 2000));
        assertTrue(LyricsFrameRenderer.hasDegenerateWordTiming(line));
    }

    @Test
    public void healthyWordTimingKeepsTimedWordFill() {
        AppliedLine line = new AppliedLine();
        line.startMs = 1000;
        line.endMs = 4000;
        line.words.add(segment("a", 1000, 2000));
        line.words.add(segment("b", 2000, 3200));
        assertFalse(LyricsFrameRenderer.hasDegenerateWordTiming(line));
    }

    @Test
    public void wordlessLinesAreNeverDegenerate() {
        assertFalse(LyricsFrameRenderer.hasDegenerateWordTiming(null));
        AppliedLine empty = new AppliedLine();
        assertFalse(LyricsFrameRenderer.hasDegenerateWordTiming(empty));
        empty.words.add(null);
        assertFalse(LyricsFrameRenderer.hasDegenerateWordTiming(empty));
    }

    @Test
    public void sentenceRouteUsesTimedWordsButSharedFill() {
        assertEquals(LyricsFrameRenderer.WordGradientRoute.TIMED_WORDS,
                LyricsFrameRenderer.wordGradientRoute("Left to right (sentence)"));
    }

    @Test
    public void hyperAodBounceUsesDirectSplinePathForSyntheticRows() throws Exception {
        String source = new String(java.nio.file.Files.readAllBytes(
                new java.io.File("src/main/java/com/eza/spicyex/lyrics/LyricsFrameRenderer.java").toPath()),
                java.nio.charset.StandardCharsets.UTF_8);
        String compact = source.replaceAll("\\s+", " ");
        // Direct motion stays on for every shared style (Apple disables it via !appleStyle only);
        // lift motion comes from the Apple-aware helper so Apple lift never depends on Bounce style.
        String directCall = "wordBounceEnabled(config, line), !config.appleStyle, "
                + "liftMotion(config), individualWordBounce(config), "
                + "config.appleLift, config.appleDimPassed);";
        int first = compact.indexOf(directCall);
        int second = compact.indexOf(directCall, first + directCall.length());
        assertTrue(first >= 0);
        assertTrue(second > first);
    }

    @Test
    public void blurRefreshSkipsActiveChangesWhileScrolling() {
        // Active-line advance while held: no refresh, rows stay sharp.
        assertFalse(LyricsFrameRenderer.blurNeedsRefresh(true, true, false, true));
        // Steady hold with no change: no refresh.
        assertFalse(LyricsFrameRenderer.blurNeedsRefresh(true, false, false, true));
        // Snap-back (hold release) always refreshes so blur restores on resume.
        assertTrue(LyricsFrameRenderer.blurNeedsRefresh(true, false, true, false));
        assertTrue(LyricsFrameRenderer.blurNeedsRefresh(true, true, true, true));
        // Normal playback advance refreshes; blur off never refreshes.
        assertTrue(LyricsFrameRenderer.blurNeedsRefresh(true, true, false, false));
        assertFalse(LyricsFrameRenderer.blurNeedsRefresh(false, true, true, false));
    }

    private static SyllableSegment segment(String text, long startMs, long endMs) {
        SyllableSegment seg = new SyllableSegment();
        seg.text = text;
        seg.startMs = startMs;
        seg.endMs = endMs;
        seg.totalMs = Math.max(0L, endMs - startMs);
        return seg;
    }
}
