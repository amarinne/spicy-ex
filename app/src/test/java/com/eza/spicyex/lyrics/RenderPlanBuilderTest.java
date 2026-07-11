package com.eza.spicyex.lyrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

import com.eza.spicyex.lyrics.reading.DefaultCanonicalLineBuilder;
import com.eza.spicyex.lyrics.reading.DefaultRenderPlanBuilder;
import com.eza.spicyex.lyrics.reading.KoreanReadingProcessor;
import com.eza.spicyex.lyrics.reading.ReadingModels.CanonicalLine;
import com.eza.spicyex.lyrics.reading.ReadingModels.ParagraphProvenance;
import com.eza.spicyex.lyrics.reading.ReadingModels.ParsedLine;
import com.eza.spicyex.lyrics.reading.ReadingModels.RenderPlan;
import com.eza.spicyex.lyrics.reading.ReadingModels.SourceSpan;

public class RenderPlanBuilderTest {
    @Test
    public void eachProviderSpanOwnsOneTimedUnit() {
        ParsedLine line = new ParsedLine("mixed", "주저 없이 다, Probably delete it", Arrays.asList(
                new SourceSpan("s0", "주", "주", 0, 100, true, null),
                new SourceSpan("s1", "저", "저", 100, 200, false, null),
                new SourceSpan("s2", "없", "없", 200, 300, true, null),
                new SourceSpan("s3", "이", "이", 300, 400, false, null),
                new SourceSpan("s4", "다,", "다,", 400, 500, false, null),
                new SourceSpan("s5", "Probably", "Probably", 500, 600, false, null),
                new SourceSpan("s6", "delete", "delete", 600, 700, false, null),
                new SourceSpan("s7", "it", "it", 700, 800, false, null)
        ), null, ParagraphProvenance.UNAVAILABLE, Collections.emptyMap());
        CanonicalLine canonical = new DefaultCanonicalLineBuilder().build(line);
        RenderPlan plan = new DefaultRenderPlanBuilder().build(line, canonical,
                Collections.singletonList(KoreanReadingProcessor.annotate(canonical, KoreanDisplayMode.VN_PRONUNCIATION)));
        assertEquals("jujo opssi da, Probably delete it", plan.joinedDisplayText);
        assertEquals(line.spans.size(), plan.timedReadingUnits.size());
        assertTrue(DefaultRenderPlanBuilder.validate(plan).errors.toString(), DefaultRenderPlanBuilder.validate(plan).valid);
    }
}
