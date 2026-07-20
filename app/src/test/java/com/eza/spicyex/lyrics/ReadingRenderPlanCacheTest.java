package com.eza.spicyex.lyrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

import com.eza.spicyex.lyrics.reading.ReadingModels.ReadingProvenance;
import com.eza.spicyex.lyrics.reading.ReadingModels.CanonicalSpanMapping;
import com.eza.spicyex.lyrics.reading.ReadingModels.ReadingUnit;
import com.eza.spicyex.lyrics.reading.ReadingModels.ReadingUnitKind;
import com.eza.spicyex.lyrics.reading.ReadingModels.RenderPlan;
import com.eza.spicyex.lyrics.reading.ReadingModels.TextRange;
import com.eza.spicyex.lyrics.reading.ReadingModels.TimedReadingUnit;

public class ReadingRenderPlanCacheTest {
    @Test
    public void renderPlanRoundTripsWithProvenanceAndTimingRefs() {
        ReadingUnit unit = new ReadingUnit(new TextRange(0, 1), "ju", ReadingUnitKind.TRANSFORMED,
                "g0", Collections.singletonList("s0"));
        RenderPlan plan = new RenderPlan("line", Collections.emptyList(), Collections.singletonList(unit),
                Arrays.asList(new TimedReadingUnit("s0", new TextRange(0, 1), "ju", "g0")), "ju", null);
        RenderPlan restored = ProcessedLyricsCache.parseRenderPlan(ProcessedLyricsCache.renderPlanToJson(plan));
        assertNotNull(restored);
        assertEquals("ju", restored.joinedDisplayText);
        assertEquals("s0", restored.timedReadingUnits.get(0).spanId);
        assertEquals(ReadingUnitKind.TRANSFORMED, restored.readingUnits.get(0).kind);
    }

    @Test
    public void cachedPlanRejectsCompetingJapaneseRomaji() {
        LyricsLine line = new LyricsLine();
        line.text = "君";
        RenderPlan plan = new RenderPlan("line", Collections.singletonList(
                new CanonicalSpanMapping("s0", new TextRange(0, 1))), Collections.singletonList(
                new ReadingUnit(new TextRange(0, 1), "kimi", ReadingUnitKind.TRANSFORMED,
                        "jp-0", Collections.singletonList("s0"))), Collections.singletonList(
                new TimedReadingUnit("s0", new TextRange(0, 1), "kimi", "jp-0")), "kimi", null);
        SpicyJapaneseChineseProcessor.JapaneseReading stale =
                new SpicyJapaneseChineseProcessor.JapaneseReading("君", "kun", Collections.emptyList());

        assertFalse(ProcessedLyricsCache.validPlanForLine(line, plan, stale));
    }
}
