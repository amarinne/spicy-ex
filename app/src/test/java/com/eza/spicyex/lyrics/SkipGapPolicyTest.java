package com.eza.spicyex.lyrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class SkipGapPolicyTest {

    private static LyricsLine vocal(long startMs, long endMs) {
        LyricsLine line = new LyricsLine();
        line.startMs = startMs;
        line.endMs = endMs;
        return line;
    }

    /** Intro dot [0, first vocal start] + vocal + mid dot + vocal + outro dot to duration. */
    private static List<AppliedLine> docWithIntroMidOutro() {
        List<AppliedLine> rows = new ArrayList<>();
        rows.add(LyricTimeline.createAppliedDotRow(0, 10000, false));
        rows.add(LyricTimeline.createAppliedVocalRow(vocal(10000, 15000), 10000, 15000));
        rows.add(LyricTimeline.createAppliedDotRow(15000, 25000, false));
        rows.add(LyricTimeline.createAppliedVocalRow(vocal(25000, 30000), 25000, 30000));
        rows.add(LyricTimeline.createAppliedDotRow(30000, 60000, false));
        return rows;
    }

    @Test
    public void introGapLandsOnFirstVocalStart() {
        SkipGapPolicy.SkipTarget target = SkipGapPolicy.skipTarget(docWithIntroMidOutro(), 2000);
        assertNotNull(target);
        assertEquals(0, target.gapStartMs);
        assertEquals(10000, target.targetMs);
    }

    @Test
    public void midGapLandsOnNextVocalStart() {
        SkipGapPolicy.SkipTarget target = SkipGapPolicy.skipTarget(docWithIntroMidOutro(), 20000);
        assertNotNull(target);
        assertEquals(15000, target.gapStartMs);
        assertEquals(25000, target.targetMs);
    }

    @Test
    public void outroGapLandsOneSecondBeforeTrackEnd() {
        SkipGapPolicy.SkipTarget target = SkipGapPolicy.skipTarget(docWithIntroMidOutro(), 45000);
        assertNotNull(target);
        assertEquals(30000, target.gapStartMs);
        assertEquals(59000, target.targetMs);
    }

    @Test
    public void vocalPositionHasNoTarget() {
        assertNull(SkipGapPolicy.skipTarget(docWithIntroMidOutro(), 12000));
        assertNull(SkipGapPolicy.skipTarget(docWithIntroMidOutro(), 27000));
    }

    @Test
    public void pastTrackEndHasNoTarget() {
        assertNull(SkipGapPolicy.skipTarget(docWithIntroMidOutro(), 60000));
        assertNull(SkipGapPolicy.skipTarget(docWithIntroMidOutro(), 99999));
    }

    @Test
    public void nullEmptyAndNegativeAreSafe() {
        assertNull(SkipGapPolicy.skipTarget(null, 1000));
        assertNull(SkipGapPolicy.skipTarget(new ArrayList<AppliedLine>(), 1000));
        assertNull(SkipGapPolicy.skipTarget(docWithIntroMidOutro(), -1));
    }
}
