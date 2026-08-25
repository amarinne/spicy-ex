package com.eza.spicyex.lyrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.eza.spicyex.lyrics.reading.ReadingModels.RenderPlan;
import com.eza.spicyex.lyrics.reading.ReadingPlanFactory;

import org.junit.Test;

public class ReadingPlanFactoryTest {
    @Test
    public void splitChinesePhraseKeepsAuthoritativeSpacesAndNeutralTone() {
        LyricsLine line = chineseLine("只要记得你是你呀 Oh",
                "只要", "记", "得", "你", "是", "你", "呀", "Oh");
        String display = romanize(line.text);
        String rawChunks = joinedSegmentReadings(line);

        assertNotEquals("fixture must reproduce whole-line versus isolated-span drift",
                withoutWhitespace(display), withoutWhitespace(rawChunks));

        RenderPlan plan = ReadingPlanFactory.timedLegacy(line, display, "LocalScript");

        assertNotNull(plan);
        assertEquals(display, plan.joinedDisplayText);
        assertTrue(plan.joinedDisplayText.contains(" jì de "));
        assertTrue(plan.joinedDisplayText.endsWith(" yā Oh"));
        assertEquals(line.syllables.size(), plan.timedReadingUnits.size());
    }

    @Test
    public void singleCharacterChineseSpansStillKeepEverySpace() {
        LyricsLine line = chineseLine("只要记得你是你呀 Oh",
                "只", "要", "记", "得", "你", "是", "你", "呀", "Oh");
        String display = romanize(line.text);

        RenderPlan plan = ReadingPlanFactory.timedLegacy(line, display, "LocalScript");

        assertNotNull(plan);
        assertEquals(display, plan.joinedDisplayText);
        assertFalse(plan.joinedDisplayText.contains("yàojì"));
        assertFalse(plan.joinedDisplayText.endsWith("yāOh"));
    }

    @Test
    public void polyphoneMismatchFailsClosedToWholeLineReading() {
        LyricsLine line = chineseLine("银行", "银", "行");
        String display = romanize(line.text);

        RenderPlan plan = ReadingPlanFactory.timedLegacy(line, display, "LocalScript");

        assertNotNull(plan);
        assertEquals(display, plan.joinedDisplayText);
        assertEquals("yín háng", display);
        assertTrue("unsafe per-span timing must be dropped", plan.timedReadingUnits.isEmpty());
        assertEquals("local-line-fallback", plan.readingUnits.get(0).logicalGroupId);
    }

    private static LyricsLine chineseLine(String text, String... spans) {
        LyricsLine line = new LyricsLine();
        line.text = text;
        line.startMs = 1_000L;
        line.endMs = line.startMs + spans.length * 300L;
        long cursor = line.startMs;
        for (int index = 0; index < spans.length; index++) {
            String source = spans[index];
            SyllableSegment segment = new SyllableSegment();
            segment.spanId = String.valueOf(index);
            segment.text = source.trim();
            segment.sourceText = source;
            segment.romanizedText = romanize(segment.text);
            segment.startMs = cursor;
            segment.endMs = cursor + 300L;
            segment.providerPartOfWord = index < spans.length - 1;
            line.syllables.add(segment);
            cursor = segment.endMs;
        }
        return line;
    }

    private static String romanize(String text) {
        return SpicyJapaneseChineseProcessor.romanizeChineseLine(text, "pinyin", true);
    }

    private static String joinedSegmentReadings(LyricsLine line) {
        StringBuilder out = new StringBuilder();
        for (SyllableSegment segment : line.syllables) out.append(segment.romanizedText);
        return out.toString();
    }

    private static String withoutWhitespace(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "");
    }
}
