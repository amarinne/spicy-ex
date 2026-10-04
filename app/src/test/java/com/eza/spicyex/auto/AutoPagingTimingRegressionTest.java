package com.eza.spicyex.auto;

import com.eza.spicyex.lyrics.AppliedLine;
import com.eza.spicyex.lyrics.SyllableSegment;
import java.lang.reflect.Method;
import org.junit.Test;
import static org.junit.Assert.*;

public class AutoPagingTimingRegressionTest {
    @Test public void firstPageShouldFinishBeforeSecondPageStarts() throws Exception {
        AppliedLine source = row();
        assertEquals(10, AutoLyricsView.sourceOffset(source, 2000));
        assertEquals("first page should be fully sung at its boundary", gradient(source, 4000),
                gradient(AutoLyricsView.slice(source, 0, 10), 2000), .001f);
    }

    @Test public void secondPageShouldStartUnsung() throws Exception {
        AppliedLine source = row();
        assertEquals("second page should start unsung", gradient(source, 0),
                gradient(AutoLyricsView.slice(source, 10, 20), 2000), .001f);
    }

    @Test public void splitTimedTokenShouldFinishItsFirstPage() throws Exception {
        AppliedLine source = row();
        source.syntheticWords = false;
        SyllableSegment word = new SyllableSegment();
        word.text = source.text; word.startMs = 0; word.endMs = 4000;
        word.canonicalStartCp = 0; word.canonicalEndCp = 20;
        source.words.add(word);
        assertEquals(10, AutoLyricsView.sourceOffset(source, 2000));
        assertEquals("split token should finish first page at boundary", gradient(source, 4000),
                gradient(AutoLyricsView.slice(source, 0, 10), 2000), .001f);
    }

    @Test public void codePointsAndNestedPagesPreserveAuthoredTimingOnSeek() throws Exception {
        AppliedLine source = row(); source.text = "🎵a🎵b";
        AppliedLine page = AutoLyricsView.slice(source, 3, 6);
        assertEquals(3, AutoLyricsView.sourceOffset(source, 2000));
        assertEquals(-40f, gradient(page, 2000), .001f);
        assertEquals(30f, gradient(page, 3000), .001f);
        assertEquals(-40f, gradient(page, 500), .001f);
        assertEquals(100f, gradient(page, 4500), .001f);
        assertEquals(0, page.startMs); assertEquals(4000, page.endMs);
        AppliedLine nested = AutoLyricsView.slice(AutoLyricsView.slice(source, 0, 6), 3, 6);
        assertEquals(gradient(page, 3000), gradient(nested, 3000), .001f);
    }

    @Test public void pageSelectionAndFillHoldTogetherAcrossTimedGapAndBackwardSeek() throws Exception {
        AppliedLine source = row(); source.syntheticWords = false;
        source.words.add(word("abcdefghij", 0, 1000, 0, 10));
        source.words.add(word("klmnopqrst", 3000, 4000, 10, 20));
        AppliedLine first = AutoLyricsView.slice(source, 0, 10);
        AppliedLine second = AutoLyricsView.slice(source, 10, 20);
        assertEquals(10, AutoLyricsView.sourceOffset(source, 2000));
        assertEquals(100f, gradient(first, 2000), .001f);
        assertEquals(-40f, gradient(second, 2000), .001f);
        assertEquals(5, AutoLyricsView.sourceOffset(source, 500));
        assertEquals(30f, gradient(first, 500), .001f);
        assertEquals(15, AutoLyricsView.sourceOffset(source, 3500));
        assertEquals(30f, gradient(second, 3500), .001f);
        assertEquals(0, first.words.get(0).startMs);
        assertEquals(1000, first.words.get(0).endMs);
    }

    @Test public void degenerateWordTimingUsesSharedSentenceFallbackForSwitchAndFill() throws Exception {
        AppliedLine source = row(); source.syntheticWords = false;
        source.words.add(word(source.text, 0, 50, 0, 20));
        assertEquals(10, AutoLyricsView.sourceOffset(source, 2000));
        assertEquals(100f, gradient(AutoLyricsView.slice(source, 0, 10), 2000), .001f);
        assertEquals(-40f, gradient(AutoLyricsView.slice(source, 10, 20), 2000), .001f);
    }

    private static SyllableSegment word(String text, long start, long end, int from, int to) {
        SyllableSegment word = new SyllableSegment(); word.text = text;
        word.startMs = start; word.endMs = end;
        word.canonicalStartCp = from; word.canonicalEndCp = to; return word;
    }

    private static AppliedLine row() {
        AppliedLine line = new AppliedLine();
        line.text = "abcdefghijklmnopqrst";
        line.startMs = 0; line.endMs = 4000; line.totalMs = 4000; line.syntheticWords = true;
        return line;
    }

    private static float gradient(AppliedLine line, long position) throws Exception {
        Class<?> planner = Class.forName("com.eza.spicyex.lyrics.SentenceGradientPlanner");
        Method method = planner.getDeclaredMethod("gradientPosition", AppliedLine.class, long.class);
        method.setAccessible(true);
        return (Float) method.invoke(null, line, position);
    }
}
