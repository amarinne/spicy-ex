package com.eza.spicyex.lyrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class JmdictFuriganaLoaderTest {
    @Test
    public void bomOnFirstLineDoesNotPolluteFirstSurfaceKey() {
        List<SpicyJapaneseChineseProcessor.FuriganaSegment> segments =
                SpicyJapaneseChineseProcessor.kanaReadingSegmentsForTest("〃", "おなじ");

        assertEquals(1, segments.size());
        assertEquals("おなじ", segments.get(0).reading);
        assertFalse(segments.get(0).reading.startsWith("\uFEFF"));
    }

    @Test
    public void knownCompoundSplitsPerKanjiFromJmdict() {
        assertEquals(Arrays.asList("最=さい", "後=ご"), furigana("最後"));
    }

    @Test
    public void missingJmdictEntryFallsBackToWholeKanjiRunRuby() {
        List<SpicyJapaneseChineseProcessor.FuriganaSegment> segments =
                SpicyJapaneseChineseProcessor.kanaReadingSegmentsForTest("仮仮", "かりかり");

        assertEquals(1, segments.size());
        assertEquals(0, segments.get(0).start);
        assertEquals(2, segments.get(0).end);
        assertEquals("かりかり", segments.get(0).reading);
    }

    @Test
    public void parseJmdictSpanSpecSupportsSingleIndexAndInclusiveRange() {
        List<SpicyJapaneseChineseProcessor.FuriganaSegment> segments =
                SpicyJapaneseChineseProcessor.parseJmdictSpanSpecForTest("0:ヨミ;2-3:かな");

        assertEquals(2, segments.size());
        assertEquals(0, segments.get(0).start);
        assertEquals(1, segments.get(0).end);
        assertEquals("よみ", segments.get(0).reading);
        assertEquals(2, segments.get(1).start);
        assertEquals(4, segments.get(1).end);
        assertEquals("かな", segments.get(1).reading);
    }

    private static List<String> furigana(String line) {
        SpicyJapaneseChineseProcessor.JapaneseReading reading =
                SpicyJapaneseChineseProcessor.analyzeJapaneseLine(line, null);
        assertNotNull(reading);
        ArrayList<String> out = new ArrayList<>();
        for (SpicyJapaneseChineseProcessor.FuriganaSegment segment : reading.furigana) {
            out.add(reading.sourceText.substring(segment.start, Math.min(segment.end, reading.sourceText.length()))
                    + "=" + segment.reading);
        }
        return out;
    }
}
