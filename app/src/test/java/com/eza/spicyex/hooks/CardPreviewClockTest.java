package com.eza.spicyex.hooks;

import com.eza.spicyex.lyrics.AppliedLine;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;

/** Replay and next-line taps must land on a line start, not on an arbitrary clock offset. */
public class CardPreviewClockTest {

    private static AppliedLine line(long startMs, long endMs) {
        AppliedLine line = new AppliedLine();
        line.startMs = startMs;
        line.endMs = endMs;
        line.text = "line";
        return line;
    }

    private static final List<AppliedLine> LINES = Arrays.asList(
            line(0, 1000),
            line(1000, 3000),
            line(5000, 7000));

    @Test public void positionWrapsAndNeverGoesNegative() {
        assertEquals(250, CardPreviewClock.position(1250, 1000, 1000));
        assertEquals(0, CardPreviewClock.position(1000, 2000, 1000));
        assertEquals(0, CardPreviewClock.position(1000, 0, 0));
    }

    @Test public void replayTargetsTheLineThatContainsThePosition() {
        assertEquals(0, CardPreviewClock.lineStartAt(LINES, 250));
        assertEquals(1000, CardPreviewClock.lineStartAt(LINES, 1200));
        assertEquals(1000, CardPreviewClock.lineStartAt(LINES, 3000));
        assertEquals(1000, CardPreviewClock.lineStartAt(LINES, 4000));
    }

    @Test public void nextLineAdvancesAndWrapsAtTheEnd() {
        assertEquals(1000, CardPreviewClock.nextLineStartAt(LINES, 250));
        assertEquals(5000, CardPreviewClock.nextLineStartAt(LINES, 1200));
        assertEquals(5000, CardPreviewClock.nextLineStartAt(LINES, 3000));
        assertEquals(5000, CardPreviewClock.nextLineStartAt(LINES, 4000));
        assertEquals(0, CardPreviewClock.nextLineStartAt(LINES, 5500));
    }

    @Test public void anEmptyDocumentFallsBackToItsStart() {
        assertEquals(0, CardPreviewClock.lineStartAt(Collections.emptyList(), 500));
        assertEquals(0, CardPreviewClock.nextLineStartAt(Collections.emptyList(), 500));
        assertEquals(0, CardPreviewClock.lineStartAt(null, 500));
    }
}
