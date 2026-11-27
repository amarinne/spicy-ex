package com.eza.spicyex.auto;

import com.eza.spicyex.lyrics.AppliedLine;
import com.eza.spicyex.lyrics.DisplayLayoutGroup;
import com.eza.spicyex.lyrics.SyllableSegment;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

public class AutoRepeatedLineTest {
    @Test public void lineSyncedRepeatMatchesAndRebindsGradientTiming() throws Exception {
        AppliedLine first = lineRow("Hold on", 1000, 3000);
        AppliedLine second = lineRow("Hold on", 3000, 5000);
        assertTrue(AutoRepeatedLine.sameRenderedLine(first, second, true, true));

        AppliedLine mounted = AutoLyricsView.slice(first, 0, first.text.length());
        assertEquals(100f, gradient(mounted, 3000), .001f);

        assertTrue(AutoRepeatedLine.rebindTiming(mounted, second, 0, second.text.length()));
        assertEquals(3000, mounted.startMs);
        assertEquals(5000, mounted.endMs);
        assertEquals(2000, mounted.totalMs);
        assertSame(second, mounted.gradientSource);
        assertEquals(-40f, gradient(mounted, 3000), .001f);
        assertEquals(30f, gradient(mounted, 4000), .001f);
        assertEquals(100f, gradient(mounted, 5000), .001f);
    }

    @Test public void wordSyncedRepeatPreservesSegmentInstancesAndUpdatesTiming() {
        AppliedLine first = wordRow("La la", 1000, 3000,
                word("La", 1000, 1800, 0, 2, true),
                word("la", 2000, 3000, 3, 5, true));
        AppliedLine second = wordRow("La la", 3000, 5200,
                word("La", 3000, 3900, 0, 2, true),
                word("la", 4100, 5200, 3, 5, true));
        assertTrue(AutoRepeatedLine.sameRenderedLine(first, second, false, false));

        AppliedLine mounted = AutoLyricsView.slice(first, 0, first.text.length());
        SyllableSegment firstRef = mounted.words.get(0);
        SyllableSegment secondRef = mounted.words.get(1);

        assertTrue(AutoRepeatedLine.rebindTiming(mounted, second, 0, second.text.length()));
        assertSame(firstRef, mounted.words.get(0));
        assertSame(secondRef, mounted.words.get(1));
        assertEquals(3000, firstRef.startMs);
        assertEquals(3900, firstRef.endMs);
        assertEquals(900, firstRef.totalMs);
        assertEquals(4100, secondRef.startMs);
        assertEquals(5200, secondRef.endMs);
        assertEquals(1100, secondRef.totalMs);
    }

    @Test public void slicedPageRebindsOnlyWordsInsideMountedRange() {
        AppliedLine first = wordRow("alpha beta gamma", 0, 3000,
                word("alpha", 0, 900, 0, 5, true),
                word("beta", 1000, 1900, 6, 10, true),
                word("gamma", 2000, 3000, 11, 16, true));
        AppliedLine second = wordRow("alpha beta gamma", 4000, 7000,
                word("alpha", 4000, 4900, 0, 5, true),
                word("beta", 5000, 5900, 6, 10, true),
                word("gamma", 6000, 7000, 11, 16, true));

        AppliedLine mountedSecondPage = AutoLyricsView.slice(first, 6, 16);
        assertEquals(2, mountedSecondPage.words.size());
        SyllableSegment betaRef = mountedSecondPage.words.get(0);
        SyllableSegment gammaRef = mountedSecondPage.words.get(1);

        assertTrue(AutoRepeatedLine.rebindTiming(mountedSecondPage, second, 6, 16));
        assertSame(betaRef, mountedSecondPage.words.get(0));
        assertSame(gammaRef, mountedSecondPage.words.get(1));
        assertEquals(5000, betaRef.startMs);
        assertEquals(5900, betaRef.endMs);
        assertEquals(6000, gammaRef.startMs);
        assertEquals(7000, gammaRef.endMs);
    }

    @Test public void secondaryDifferencesGateOnlyWhenDisplayed() {
        AppliedLine first = lineRow("愛", 0, 1000);
        first.romanizedText = "ai";
        first.translatedText = "love";
        AppliedLine second = lineRow("愛", 1000, 2000);
        second.romanizedText = "ai!";
        second.translatedText = "beloved";

        assertTrue(AutoRepeatedLine.sameRenderedLine(first, second, false, false));
        assertFalse(AutoRepeatedLine.sameRenderedLine(first, second, true, false));
        assertFalse(AutoRepeatedLine.sameRenderedLine(first, second, false, true));
        assertFalse(AutoRepeatedLine.sameRenderedLine(first, second, true, true));

        second.romanizedText = "ai";
        assertTrue(AutoRepeatedLine.sameRenderedLine(first, second, true, false));
        assertFalse(AutoRepeatedLine.sameRenderedLine(first, second, true, true));
    }

    @Test public void rejectsTextSyncModeTokenizationAndLayoutGroupMismatches() {
        AppliedLine base = wordRow("La la", 0, 2000,
                word("La", 0, 900, 0, 2, true),
                word("la", 1000, 2000, 3, 5, true));

        assertFalse("case difference changes rendered glyphs",
                AutoRepeatedLine.sameRenderedLine(base, lineRow("la la", 2000, 4000), false, false));
        assertFalse("word vs line sync builds different view trees",
                AutoRepeatedLine.sameRenderedLine(base, lineRow("La la", 2000, 4000), false, false));

        AppliedLine differentBoundary = wordRow("La la", 2000, 4000,
                word("La", 2000, 2900, 0, 2, false),
                word("la", 3000, 4000, 3, 5, true));
        assertFalse("boundaryAfter controls word grouping",
                AutoRepeatedLine.sameRenderedLine(base, differentBoundary, false, false));

        AppliedLine singleToken = wordRow("La la", 2000, 4000,
                word("La la", 2000, 4000, 0, 5, true));
        assertFalse("different word count cannot reuse mounted word views",
                AutoRepeatedLine.sameRenderedLine(base, singleToken, false, false));

        AppliedLine grouped = lineRow("Hold on", 0, 1000);
        grouped.displayLayoutGroups = Collections.singletonList(
                new DisplayLayoutGroup(0, 7, "transport", true, 1));
        AppliedLine ungrouped = lineRow("Hold on", 1000, 2000);
        assertFalse("layout group change affects wrapping and paging",
                AutoRepeatedLine.sameRenderedLine(grouped, ungrouped, false, false));

        ungrouped.displayLayoutGroups = Collections.singletonList(
                new DisplayLayoutGroup(0, 7, "transport", true, 1));
        assertTrue(AutoRepeatedLine.sameRenderedLine(grouped, ungrouped, false, false));
    }

    @Test public void rebindLeavesMountedUntouchedOnStructuralMismatch() {
        AppliedLine first = wordRow("La la", 0, 2000,
                word("La", 0, 900, 0, 2, true),
                word("la", 1000, 2000, 3, 5, true));
        AppliedLine mounted = AutoLyricsView.slice(first, 0, first.text.length());
        AppliedLine mismatched = wordRow("La la", 2000, 4000,
                word("La la", 2000, 4000, 0, 5, true));

        assertFalse(AutoRepeatedLine.rebindTiming(mounted, mismatched, 0, mismatched.text.length()));
        assertEquals(0, mounted.startMs);
        assertEquals(2000, mounted.endMs);
        assertEquals(0, mounted.words.get(0).startMs);
    }

    private static AppliedLine lineRow(String text, long startMs, long endMs) {
        AppliedLine line = new AppliedLine();
        line.text = text;
        line.startMs = startMs;
        line.endMs = endMs;
        line.totalMs = Math.max(1, endMs - startMs);
        line.syntheticWords = true;
        return line;
    }

    private static AppliedLine wordRow(String text, long startMs, long endMs, SyllableSegment... words) {
        AppliedLine line = lineRow(text, startMs, endMs);
        line.syntheticWords = false;
        line.words.addAll(Arrays.asList(words));
        return line;
    }

    private static SyllableSegment word(String text, long startMs, long endMs, int startCp, int endCp,
                                        boolean boundaryAfter) {
        SyllableSegment seg = new SyllableSegment();
        seg.text = text;
        seg.sourceText = text;
        seg.startMs = startMs;
        seg.endMs = endMs;
        seg.totalMs = Math.max(1, endMs - startMs);
        seg.canonicalStartCp = startCp;
        seg.canonicalEndCp = endCp;
        seg.boundaryAfter = boundaryAfter;
        seg.partOfWord = !boundaryAfter;
        return seg;
    }

    private static float gradient(AppliedLine line, long position) throws Exception {
        Class<?> planner = Class.forName("com.eza.spicyex.lyrics.SentenceGradientPlanner");
        Method method = planner.getDeclaredMethod("gradientPosition", AppliedLine.class, long.class);
        method.setAccessible(true);
        return (Float) method.invoke(null, line, position);
    }
}
