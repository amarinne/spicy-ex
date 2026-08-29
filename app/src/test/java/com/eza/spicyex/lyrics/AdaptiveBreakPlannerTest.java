package com.eza.spicyex.lyrics;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class AdaptiveBreakPlannerTest {
    @Test
    public void keepsSingleLineWhenAllChildrenFit() {
        assertArrayEquals(new boolean[]{false, false, false},
                AdaptiveBreakPlanner.plan(new int[]{20, 20, 20}, 60, null, null));
    }

    @Test
    public void balancesMinimalLineCountInsteadOfKeepingGreedyBreak() {
        assertArrayEquals(new boolean[]{false, true, false, false},
                AdaptiveBreakPlanner.plan(new int[]{40, 10, 10, 10}, 50, null, null));
    }

    @Test
    public void fittingKeepTogetherGroupCannotSplit() {
        assertArrayEquals(new boolean[]{false, false, true},
                AdaptiveBreakPlanner.plan(
                        new int[]{40, 10, 10}, 50,
                        new boolean[]{true, false},
                        new int[][]{{0, 1}}));
    }

    @Test
    public void oversizedKeepTogetherGroupCanSplit() {
        assertArrayEquals(new boolean[]{false, true, false},
                AdaptiveBreakPlanner.plan(
                        new int[]{40, 20, 10}, 50,
                        new boolean[]{true, false},
                        new int[][]{{0, 1}}));
    }

    @Test
    public void oversizedSingleChildFallsBackWithoutForcedBreaks() {
        assertArrayEquals(new boolean[]{false, false},
                AdaptiveBreakPlanner.plan(new int[]{70, 10}, 50, null, null));
    }

    @Test
    public void mapsOnlyCertainAdjacentChildrenInsideSameLayoutGroup() {
        List<DisplayLayoutGroup> groups = Collections.singletonList(
                new DisplayLayoutGroup(0, 2, "phrase", true, 1.0));
        List<int[]> ranges = Arrays.asList(new int[]{0, 1}, new int[]{1, 2}, new int[]{2, 3});

        assertArrayEquals(new boolean[]{true, false},
                LyricsRowViewFactory.adaptiveForbiddenBreaks(groups, ranges));
        assertEquals(1, LyricsRowViewFactory.adaptiveKeepTogetherGroups(groups, ranges).length);
        assertArrayEquals(new int[]{0, 1},
                LyricsRowViewFactory.adaptiveKeepTogetherGroups(groups, ranges)[0]);
    }

    @Test
    public void uncertainChildRangeDoesNotJoinUnrelatedText() {
        List<DisplayLayoutGroup> groups = Collections.singletonList(
                new DisplayLayoutGroup(0, 3, "phrase", true, 1.0));
        List<int[]> ranges = Arrays.asList(new int[]{0, 1}, null, new int[]{2, 3});

        assertArrayEquals(new boolean[]{false, false},
                LyricsRowViewFactory.adaptiveForbiddenBreaks(groups, ranges));
    }

    @Test
    public void japaneseReadingForcesJapaneseLayoutClassification() {
        AppliedLine line = new AppliedLine();
        line.japaneseReading = SpicyJapaneseChineseProcessor.analyzeJapaneseLine("音楽", null);

        assertEquals("ja", LyricsRowViewFactory.adaptiveLayoutLanguage(line));
    }
}
