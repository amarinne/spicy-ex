package com.eza.spicyex.lyrics;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class SentenceGradientPlannerTest {
    @Test public void sourceTimingSurvivesRubyCoalescingAndHoldsAcrossGaps() {
        AppliedLine line = new AppliedLine();
        line.text = "日本語だ";
        line.startMs = 1000; line.endMs = 10000;
        line.sourceLine = new LyricsLine();
        line.sourceLine.syllables.add(span("日本", 1000, 2000, 0, 2));
        line.sourceLine.syllables.add(span("語だ", 4000, 8000, 2, 4));
        line.words.add(span(line.text, 1000, 8000, 0, 4));
        assertProgress(0f, line, 999);
        assertProgress(.25f, line, 1500);
        assertProgress(.5f, line, 3000);
        assertProgress(.75f, line, 6000);
        assertProgress(1f, line, 8000);
    }

    @Test public void fallbackUsesCodePointsAndOriginalFillWindow() {
        AppliedLine line = new AppliedLine();
        line.text = "🎵ab";
        line.words.add(span("🎵", 1000, 2000, -1, -1));
        line.words.add(span("ab", 2000, 4000, -1, -1));
        assertProgress(1f / 6f, line, 1500);
        line.syntheticWords = true;
        line.startMs = 1000; line.endMs = 10000;
        line.sourceLine = new LyricsLine();
        line.sourceLine.endMs = 5000;
        assertProgress(.5f, line, 3000);
        assertProgress(1f, line, 5000);
    }

    @Test public void finalPunctuationAndCollapsedSpansDoNotPreventCompletion() {
        AppliedLine line = new AppliedLine();
        line.text = "ab!"; line.startMs = 1000; line.endMs = 3000;
        line.words.add(span("a", 1000, 2000, 0, 1));
        line.words.add(span("b", 2000, 3000, 1, 2));
        line.words.add(span("!", 3000, 3000, 2, 3));
        assertProgress(0f, line, 900);
        assertProgress(1f / 6f, line, 1500);
        assertProgress(1f, line, 3000);
    }

    @Test public void compressedSourceTimingKeepsTheLineFallback() {
        AppliedLine line = new AppliedLine();
        line.text = "ab"; line.startMs = 1000; line.endMs = 11000;
        line.sourceLine = new LyricsLine();
        line.sourceLine.syllables.add(span("ab", 1000, 1100, 0, 2));
        assertProgress(.5f, line, 6000);
    }

    @Test public void backgroundUsesItsOwnWordsInsteadOfLeadVocalTiming() {
        AppliedLine line = new AppliedLine();
        line.text = "ab"; line.startMs = 3000; line.endMs = 5000; line.bgLine = true;
        line.sourceLine = new LyricsLine();
        line.sourceLine.syllables.add(span("lead", 1000, 10000, 0, 4));
        line.words.add(span("ab", 3000, 5000, 0, 2));
        assertProgress(0f, line, 2000);
        assertProgress(.5f, line, 4000);
        assertProgress(1f, line, 5000);
    }

    private static void assertProgress(float expected, AppliedLine line, long position) {
        assertEquals(LyricAnimations.gradientPosition(expected),
                SentenceGradientPlanner.gradientPosition(line, position), .001f);
    }

    private static SyllableSegment span(String text, long start, long end, int from, int to) {
        SyllableSegment span = new SyllableSegment();
        span.text = text; span.startMs = start; span.endMs = end;
        span.canonicalStartCp = from; span.canonicalEndCp = to;
        return span;
    }
}
