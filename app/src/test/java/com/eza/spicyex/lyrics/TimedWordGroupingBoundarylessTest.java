package com.eza.spicyex.lyrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

/** Character-timed lines with no provider word boundary anywhere (QQ QRC CJK). */
public class TimedWordGroupingBoundarylessTest {
    @Test
    public void chineseLineWithoutBoundariesIsNotOneUnwrappableWord() {
        AppliedLine line = line("我爱你中国", "我", "爱", "你", "中", "国");

        for (int index = 0; index < line.words.size(); index++) {
            assertEquals(index, TimedWordGrouping.groupEnd(line, index));
            assertFalse(TimedWordGrouping.isGrouped(line, index));
        }
    }

    @Test
    public void koreanLineWithoutBoundariesGroupsSyllablesBetweenPunctuation() {
        AppliedLine line = line("사랑해,너를", "사", "랑", "해", ",", "너", "를");

        assertEquals(2, TimedWordGrouping.groupEnd(line, 0));
        assertEquals(3, TimedWordGrouping.groupEnd(line, 3));
        assertEquals(5, TimedWordGrouping.groupEnd(line, 4));
    }

    @Test
    public void anyProviderBoundaryKeepsBoundaryAfterAsTheRule() {
        AppliedLine line = line("我爱你中国", "我", "爱", "你", "中", "国");
        line.words.get(2).boundaryAfter = true;

        assertEquals(2, TimedWordGrouping.groupEnd(line, 0));
        assertEquals(4, TimedWordGrouping.groupEnd(line, 3));
    }

    private static AppliedLine line(String text, String... parts) {
        AppliedLine line = new AppliedLine();
        line.text = text;
        long start = 1000L;
        for (String part : parts) {
            SyllableSegment segment = new SyllableSegment();
            segment.text = part;
            segment.sourceText = part;
            segment.startMs = start;
            segment.endMs = start + 200L;
            segment.totalMs = 200L;
            line.words.add(segment);
            start += 200L;
        }
        return line;
    }
}
