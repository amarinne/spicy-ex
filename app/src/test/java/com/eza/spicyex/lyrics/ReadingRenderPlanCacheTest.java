package com.eza.spicyex.lyrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

import com.eza.spicyex.lyrics.reading.ReadingModels.ReadingProvenance;
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
}
