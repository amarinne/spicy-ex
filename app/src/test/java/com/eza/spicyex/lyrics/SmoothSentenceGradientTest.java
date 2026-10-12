package com.eza.spicyex.lyrics;

import org.junit.Test;
import static org.junit.Assert.*;

public class SmoothSentenceGradientTest {
    @Test public void adjoiningWordsMatchSpeedWithoutStoppingAtTheBoundary() {
        AppliedLine line = line("ab", span("a", 0, 1000, 0, 1), span("b", 1000, 1200, 1, 2));
        assertEquals(.5f, progress(line, 1000), .00001f);
        float incoming = progress(line, 1000) - progress(line, 999);
        float outgoing = progress(line, 1001) - progress(line, 1000);
        assertTrue(incoming > .0005f);
        // One-millisecond samples include curvature on the shorter word.
        assertEquals(incoming, outgoing, .00004f);
        assertMonotone(line, 1200);
        // Non-Auto retains the original piecewise linear motion.
        line.smoothSentenceGradient = false;
        assertEquals(.25f, progress(line, 500), .00001f);
        assertTrue(progress(line, 1001) - progress(line, 1000) > incoming * 2f);
    }

    @Test public void realGapsHoldExactlyAndTheWaveEasesToRest() {
        AppliedLine line = line("ab", span("a", 1000, 2000, 0, 1), span("b", 4000, 5000, 1, 2));
        assertEquals(0f, progress(line, 999), .00001f);
        assertEquals(.078125f, progress(line, 1250), .00001f);
        assertEquals(.5f, progress(line, 2000), .00001f);
        assertEquals(progress(line, 2000), progress(line, 3000), 0f);
        assertEquals(progress(line, 3000), progress(line, 4000), 0f);
        assertTrue(progress(line, 2000) - progress(line, 1999) < .00001f);
        assertTrue(progress(line, 4001) - progress(line, 4000) < .00001f);
        assertEquals(1f, progress(line, 5000), .00001f);
        assertEquals(.078125f, progress(line, 1250), .00001f); // A backward seek has no lag.
        assertMonotone(line, 5000);
    }

    @Test public void canonicalSeparatorsAndPunctuationDoNotJumpAtWordBoundaries() {
        AppliedLine line = line("a b!", span("a", 0, 1000, 0, 1), span("b", 1000, 2000, 2, 3));
        assertEquals(.5f, progress(line, 1000), .00001f);
        assertTrue(progress(line, 1000) - progress(line, 999) < .001f);
        assertTrue(progress(line, 2000) - progress(line, 1999) < .00001f);
        assertMonotone(line, 2000);
    }

    @Test public void sourceSpansDriveCoalescedAndCroppedWords() {
        AppliedLine source = line("abcd", span("ab", 0, 1000, 0, 2), span("cd", 1000, 3000, 2, 4));
        source.sourceLine = new LyricsLine();
        source.sourceLine.syllables.addAll(source.words);
        source.words.clear(); source.words.add(span("abcd", 0, 3000, 0, 4));
        assertEquals(.5f, progress(source, 1000), .00001f);
        AppliedLine page = new AppliedLine();
        page.text = "cd"; page.gradientSource = source; page.gradientStartCp = 2; page.gradientEndCp = 4;
        page.smoothSentenceGradient = true;
        source.smoothSentenceGradient = false; // Paging keeps its original source cursor.
        assertEquals(0f, progress(page, 1000), .00001f);
        assertEquals(.6666667f, progress(page, 2000), .00001f);
        assertEquals(1f, progress(page, 3000), .00001f);
        assertEquals(0f, progress(page, 1000), .00001f);
    }

    @Test public void fallbackAndOverlappingSpansStayBounded() {
        AppliedLine line = line("🎵ab", span("🎵", 0, 1000, -1, -1), span("ab", 1000, 2000, -1, -1));
        assertEquals(1f / 3f, progress(line, 1000), .00001f);
        assertMonotone(line, 2000);
        line = line("abc!", span("ab", 0, 1000, 0, 2), null,
                span("bc", 500, 1500, 1, 3), span("!", 1500, 1500, 3, 4));
        assertMonotone(line, 1500);
        line.syntheticWords = true;
        assertEquals(.5f, progress(line, 750), .00001f);
    }

    private static void assertMonotone(AppliedLine line, int end) {
        float previous = 0f;
        for (int time = 0; time <= end; time++) {
            float current = progress(line, time);
            assertTrue("backward or out of bounds at " + time, current >= previous && current <= 1f);
            previous = current;
        }
        assertEquals(1f, previous, .00001f);
    }

    private static float progress(AppliedLine line, long position) {
        return SentenceGradientPlanner.sourceProgress(line, position);
    }

    private static AppliedLine line(String text, SyllableSegment... spans) {
        AppliedLine line = new AppliedLine(); line.text = text; line.smoothSentenceGradient = true;
        for (SyllableSegment span : spans) {
            line.words.add(span);
            if (span != null) line.endMs = Math.max(line.endMs, span.endMs);
        }
        return line;
    }

    private static SyllableSegment span(String text, long start, long end, int from, int to) {
        SyllableSegment span = new SyllableSegment(); span.text = text;
        span.startMs = start; span.endMs = end; span.canonicalStartCp = from; span.canonicalEndCp = to;
        return span;
    }
}
