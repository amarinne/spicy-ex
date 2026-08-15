package com.eza.spicyex.lyrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ArtworkLyricWindowPlannerTest {
    @Test
    public void timedWindowUsesTimelinePrimaryLeadAndBalancesNearestContext() {
        LyricsDocument doc = timedDocument(5);
        AppliedLine background = row(2_000, 4_000);
        background.bgLine = true;
        doc.appliedLines.add(2, background);

        ArtworkLyricWindowPlanner.Window window = ArtworkLyricWindowPlanner.select(
                doc, 2_500, 50, new int[]{10, 10, 10, 10, 10, 10});

        assertEquals(0, window.startInclusive);
        assertEquals(4, window.endExclusive);
        assertEquals(1, window.anchorIndex);
        assertEquals(40, window.usedHeightPx);
    }

    @Test
    public void timedWindowCanAnchorOnInterlude() {
        LyricsDocument doc = new LyricsDocument();
        doc.type = "Line";
        doc.appliedLines.add(row(0, 2_000));
        AppliedLine interlude = row(2_000, 5_000);
        interlude.dotLine = true;
        doc.appliedLines.add(interlude);
        doc.appliedLines.add(row(5_000, 7_000));

        ArtworkLyricWindowPlanner.Window window = ArtworkLyricWindowPlanner.select(
                doc, 3_000, 20, new int[]{10, 10, 10});

        assertEquals(1, window.startInclusive);
        assertEquals(2, window.endExclusive);
        assertEquals(1, window.anchorIndex);
    }

    @Test
    public void staticLyricsAlwaysShowFirstFittedPage() {
        LyricsDocument doc = timedDocument(5);
        doc.type = "Static";

        ArtworkLyricWindowPlanner.Window window = ArtworkLyricWindowPlanner.select(
                doc, 99_000, 31, new int[]{10, 10, 10, 10, 10});

        assertEquals(0, window.startInclusive);
        assertEquals(3, window.endExclusive);
        assertEquals(0, window.anchorIndex);
        assertEquals(30, window.usedHeightPx);
    }

    @Test
    public void noActiveTimedRowProducesNoPartialWindow() {
        LyricsDocument doc = timedDocument(2);

        ArtworkLyricWindowPlanner.Window window = ArtworkLyricWindowPlanner.select(
                doc, 99_000, 100, new int[]{10, 10});

        assertTrue(window.isEmpty());
        assertEquals(-1, window.anchorIndex);
    }

    @Test
    public void oversizedAnchorRemainsTheOnlyVisibleRow() {
        LyricsDocument doc = timedDocument(3);

        ArtworkLyricWindowPlanner.Window window = ArtworkLyricWindowPlanner.select(
                doc, 2_500, 20, new int[]{10, 30, 10});

        assertEquals(1, window.startInclusive);
        assertEquals(2, window.endExclusive);
        assertEquals(30, window.usedHeightPx);
    }

    @Test
    public void timedContextUsesIndependentHalfHeightBudgets() {
        LyricsDocument doc = timedDocument(5);

        ArtworkLyricWindowPlanner.Window window = ArtworkLyricWindowPlanner.select(
                doc, 4_500, 50, new int[]{8, 8, 10, 20, 8});

        assertEquals(0, window.startInclusive);
        assertEquals(4, window.endExclusive);
        assertEquals(2, window.anchorIndex);
        assertEquals(46, window.usedHeightPx);
    }

    private static LyricsDocument timedDocument(int count) {
        LyricsDocument doc = new LyricsDocument();
        doc.type = "Line";
        for (int i = 0; i < count; i++) doc.appliedLines.add(row(i * 2_000L, (i + 1) * 2_000L));
        return doc;
    }

    private static AppliedLine row(long startMs, long endMs) {
        AppliedLine line = new AppliedLine();
        line.text = "row";
        line.startMs = startMs;
        line.endMs = endMs;
        return line;
    }
}
