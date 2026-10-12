package com.eza.spicyex.lyrics;

import com.eza.spicyex.lyrics.reading.ReadingModels.RenderPlan;
import com.eza.spicyex.lyrics.reading.ReadingModels.TextRange;
import com.eza.spicyex.lyrics.reading.ReadingModels.TimedReadingUnit;
import com.google.gson.Gson;
import java.util.Arrays;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

public class TimedWordGroupingIndexTest {
    @Test
    public void joinedSpansRequireEveryOwnerToMatch() {
        RenderPlan plan = plan(unit("a", "first"), unit("b", "first"), unit("c", "second"));
        assertEquals("first", plan.logicalGroupForSpans("a"));
        assertEquals("first", plan.logicalGroupForSpans("a+b"));
        assertNull(plan.logicalGroupForSpans("a+c"));
        assertNull(plan.logicalGroupForSpans("a+missing"));
        assertNull(plan.logicalGroupForSpans("missing"));
        // Repeated lookups exercise the memoized positive and negative cases.
        assertEquals("first", plan.logicalGroupForSpans("a+b"));
        assertNull(plan.logicalGroupForSpans("a+c"));
    }

    @Test
    public void firstMatchingUnitWinsEvenWhenItHasNoGroup() {
        RenderPlan plan = plan(null, unit(null, "ignored"), unit("a", null), unit("a", "later"),
                unit("b", ""), unit("b", "later"), unit("c", "first"), unit("c", "later"));
        assertNull(plan.logicalGroupForSpans("a"));
        assertNull(plan.logicalGroupForSpans("b"));
        assertEquals("first", plan.logicalGroupForSpans("c"));
    }

    @Test
    public void joinedSpanSeparatorsKeepExistingSplitSemantics() {
        RenderPlan plan = plan(unit("", "same"), unit("a", "same"), unit("b", "same"),
                unit("a+b", "incorrect-literal-owner"));
        assertEquals("same", plan.logicalGroupForSpans("a+b"));
        assertEquals("same", plan.logicalGroupForSpans("a+"));
        assertEquals("same", plan.logicalGroupForSpans("a++"));
        assertEquals("same", plan.logicalGroupForSpans("+a"));
        assertEquals("same", plan.logicalGroupForSpans("a++b"));
        assertNull(plan.logicalGroupForSpans("+"));
        assertNull(plan.logicalGroupForSpans("++"));
        assertNull(plan.logicalGroupForSpans(""));
        assertNull(plan.logicalGroupForSpans(null));
        assertNull(plan(unit("a", "same")).logicalGroupForSpans("+a"));
    }

    @Test
    public void replacingPlanOrChangingSegmentKeepsCurrentGrouping() {
        AppliedLine line = line(plan(unit("a", "same"), unit("b", "same"), unit("c", "other")));
        assertEquals(1, TimedWordGrouping.groupEnd(line, 0));
        line.words.get(1).spanId = "c";
        assertEquals(0, TimedWordGrouping.groupEnd(line, 0));
        line.words.get(1).spanId = "b";
        line.readingRenderPlan = plan(unit("a", "first"), unit("b", "second"));
        assertEquals(0, TimedWordGrouping.groupEnd(line, 0));
        line.readingRenderPlan = plan(unit("a", "same"), unit("b", "same"));
        assertEquals(1, TimedWordGrouping.groupEnd(line, 0));
        line.syntheticWords = true;
        assertEquals(0, TimedWordGrouping.groupEnd(line, 0));
    }

    @Test
    public void gsonCacheRestoreRebuildsIndexWithoutChangingStoredSchema() {
        Gson gson = new Gson();
        RenderPlan original = plan(unit("a", "same"), unit("b", "same"));
        String before = gson.toJson(original);
        assertEquals("same", original.logicalGroupForSpans("a+b"));
        assertEquals(before, gson.toJson(original));
        assertFalse(before.contains("logicalGroups"));
        RenderPlan restored = gson.fromJson(before, RenderPlan.class);
        assertEquals(1, TimedWordGrouping.groupEnd(line(restored), 0));
        assertEquals("same", restored.logicalGroupForSpans("a+b"));
        assertEquals(before, gson.toJson(restored));
    }

    @Test
    public void knownGroupEndUsesCurrentAuthoredTiming() {
        AppliedLine line = line(plan(unit("a", "same"), unit("b", "same")));
        line.words.get(0).startMs = 100;
        line.words.get(1).endMs = 250;
        assertEquals(250, TimedWordGrouping.endMs(line, 0, 1));
        line.words.get(1).endMs = 300;
        assertEquals(300, TimedWordGrouping.endMs(line, 0, 1));
        assertEquals(TimedWordGrouping.endMs(line, 0), TimedWordGrouping.endMs(line, 0, 1));
    }

    private static RenderPlan plan(TimedReadingUnit... units) {
        return new RenderPlan("line", null, null, Arrays.asList(units), "", "");
    }

    private static TimedReadingUnit unit(String id, String group) {
        return new TimedReadingUnit(id, new TextRange(0, 1), "", group);
    }

    private static AppliedLine line(RenderPlan plan) {
        AppliedLine line = new AppliedLine();
        line.readingRenderPlan = plan;
        for (String id : new String[]{"a", "b"}) {
            SyllableSegment segment = new SyllableSegment();
            segment.spanId = id;
            segment.boundaryAfter = true;
            line.words.add(segment);
        }
        return line;
    }
}
