package com.eza.spicyex.lyrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import com.eza.spicyex.Settings;
import com.eza.spicyex.lyrics.reading.ReadingModels.RenderPlan;
import com.eza.spicyex.lyrics.reading.ReadingModels.CanonicalSpanMapping;
import com.eza.spicyex.lyrics.reading.ReadingModels.TextRange;
import com.eza.spicyex.lyrics.reading.ReadingModels.TimedReadingUnit;

public class LyricsSurfaceRowPlannerTest {
    @Test
    public void adaptiveSectioningIsPublicAndDefaultsOn() {
        assertEquals("lyric_adaptive_sectioning", Settings.ADAPTIVE_SECTIONING.key);
        assertSame(Settings.TEXT, Settings.ADAPTIVE_SECTIONING.section);
        assertTrue(Settings.ADAPTIVE_SECTIONING.defaultValue);
    }

    @Test
    public void adaptiveSectioningSelectsOnlyTheRecentWrappingModes() {
        assertEquals(LyricsRowViewFactory.AdaptiveBreakMode.NONE,
                LyricsRowViewFactory.adaptiveBreakMode(false, true, 35));
        assertEquals(LyricsRowViewFactory.AdaptiveBreakMode.NONE,
                LyricsRowViewFactory.adaptiveBreakMode(true, true, 22));
        assertEquals(LyricsRowViewFactory.AdaptiveBreakMode.BALANCED,
                LyricsRowViewFactory.adaptiveBreakMode(true, false, 35));
        assertEquals(LyricsRowViewFactory.AdaptiveBreakMode.CJK_PHRASE,
                LyricsRowViewFactory.adaptiveBreakMode(true, true, 33));
    }

    @Test
    public void adaptiveSectioningPolicyThreadsToMountedRowOptions() {
        AppliedLine source = line("plain upstream wrapping");
        LyricsSurfaceRowPlanner.SurfacePolicy policy = new LyricsSurfaceRowPlanner.SurfacePolicy(
                1f, false, false, "off", false,
                false, false, false, false,
                "Medium", "default", 1f, false, true,
                false, false, false);

        LyricsSurfaceRowPlanner.RowPlan plan = LyricsSurfaceRowPlanner.plan(source, document(source), policy);

        assertFalse(plan.options.adaptiveSectioningEnabled);
        assertTrue(plan.options.wrapLongLines);
    }

    @Test
    public void fullscreenDisablesLiveCardHorizontalSafetyPadding() {
        AppliedLine fullscreen = line("hello bright world");

        LyricsSurfaceRowPlanner.RowPlan fullscreenPlan = LyricsSurfaceRowPlanner.plan(
                fullscreen, document(fullscreen), LyricsSurfaceRowPlanner.SurfacePolicy.fullscreen(null, false, false, "off"));

        assertFalse(fullscreenPlan.options.horizontalSafetyPadding);
    }

    @Test
    public void fullscreenAndLiveCardShareSyntheticWordPlanning() {
        AppliedLine fullscreen = line("hello bright world");
        AppliedLine liveCard = line("hello bright world");
        LyricsDocument doc = document(fullscreen);
        LyricsDocument liveDoc = document(liveCard);

        LyricsSurfaceRowPlanner.SurfacePolicy fullscreenPolicy = new LyricsSurfaceRowPlanner.SurfacePolicy(
                1f, true, true, "off", true,
                false, false, true, false,
                "Medium", "default", 1f, false, true);
        LyricsSurfaceRowPlanner.SurfacePolicy livePolicy = new LyricsSurfaceRowPlanner.SurfacePolicy(
                0.30f, true, false, "off", true,
                false, false, true, false,
                "Medium", "default", 0.68f, false, false);

        LyricsSurfaceRowPlanner.plan(fullscreen, doc, fullscreenPolicy);
        LyricsSurfaceRowPlanner.plan(liveCard, liveDoc, livePolicy);

        assertTrue(fullscreen.syntheticWords);
        assertTrue(liveCard.syntheticWords);
        assertEquals(fullscreen.words.size(), liveCard.words.size());
        for (int i = 0; i < fullscreen.words.size(); i++) {
            assertEquals(fullscreen.words.get(i).text, liveCard.words.get(i).text);
            assertEquals(fullscreen.words.get(i).startMs, liveCard.words.get(i).startMs);
            assertEquals(fullscreen.words.get(i).endMs, liveCard.words.get(i).endMs);
        }
    }

    @Test
    public void lineFallbackDoesNotEnableRendererTimeWordProcessing() {
        AppliedLine line = line("hello world");
        line.romanizedText = "heh-loh world";
        LyricsDocument doc = document(line);
        LyricsSurfaceRowPlanner.SurfacePolicy policy = new LyricsSurfaceRowPlanner.SurfacePolicy(
                1f, true, false, "off", true,
                false, false, false, false,
                "Medium", "default", 1f, false, true);

        LyricsSurfaceRowPlanner.RowPlan plan = LyricsSurfaceRowPlanner.plan(line, doc, policy);

        assertTrue(plan.options.attachTransliterationToWords);
        assertTrue(plan.options.documentText.isEmpty());
    }

    @Test
    public void noAttachedTransliterationKeepsSecondaryLineProviderOff() {
        AppliedLine line = line("hello world");
        line.romanizedText = "heh-loh world";
        LyricsDocument doc = document(line);
        LyricsSurfaceRowPlanner.SurfacePolicy policy = new LyricsSurfaceRowPlanner.SurfacePolicy(
                1f, true, false, "off", false,
                false, false, false, false,
                "Medium", "default", 1f, false, true);

        LyricsSurfaceRowPlanner.RowPlan plan = LyricsSurfaceRowPlanner.plan(line, doc, policy);

        assertFalse(plan.options.attachTransliterationToWords);
    }

    @Test
    public void japaneseFuriganaDoesNotCreateSyntheticWords() {
        AppliedLine line = line("今年 は");
        line.japaneseReading = new SpicyJapaneseChineseProcessor.JapaneseReading(
                "今年 は", "kotoshi wa", java.util.Collections.singletonList(
                new SpicyJapaneseChineseProcessor.FuriganaSegment(0, 2, "ことし")));
        LyricsSurfaceRowPlanner.SurfacePolicy policy = new LyricsSurfaceRowPlanner.SurfacePolicy(
                1f, true, false, "furigana_only", true,
                false, false, true, false,
                "Medium", "default", 1f, false, true);

        LyricsSurfaceRowPlanner.RowPlan plan = LyricsSurfaceRowPlanner.plan(line, document(line), policy);

        assertFalse(line.syntheticWords);
        assertTrue(line.words.isEmpty());
        assertTrue(plan.options.showJapaneseFurigana);
        assertNotNull(plan.options);
    }

    @Test
    public void japaneseFuriganaCrossingTimedWordsRequiresLineLevelRendering() {
        AppliedLine line = line("今年も早いね");
        line.words.add(word("今", false));
        line.words.add(word("年", true));
        line.words.add(word("も", false));
        line.words.add(word("早い", false));
        line.words.add(word("ね", false));
        line.japaneseReading = new SpicyJapaneseChineseProcessor.JapaneseReading(
                line.text, "kotoshi mo hayai ne", java.util.Collections.singletonList(
                new SpicyJapaneseChineseProcessor.FuriganaSegment(0, 2, "ことし")));

        assertTrue(FuriganaText.hasRubyCrossingWordBoundaries(line));
    }

    @Test
    public void jukujikunRubyCoalescesOnlyCrossedTimingSpans() {
        AppliedLine line = line("「今年も早いね」と");
        String[] texts = {"「今", "年", "も", "早", "いね」", "と"};
        java.util.ArrayList<TimedReadingUnit> timed = new java.util.ArrayList<>();
        int cp = 0;
        for (int i = 0; i < texts.length; i++) {
            SyllableSegment segment = word(texts[i], i > 0);
            segment.startMs = 1000L + i * 100L;
            segment.endMs = segment.startMs + 100L;
            line.words.add(segment);
            int next = cp + texts[i].codePointCount(0, texts[i].length());
            timed.add(new TimedReadingUnit(String.valueOf(i), new TextRange(cp, next),
                    i == 0 ? "kotoshi" : "", "jp-0"));
            cp = next;
        }
        line.japaneseReading = new SpicyJapaneseChineseProcessor.JapaneseReading(
                line.text, "kotoshi mo hayai ne to", java.util.Collections.singletonList(
                new SpicyJapaneseChineseProcessor.FuriganaSegment(1, 3, "ことし")));
        line.readingRenderPlan = new RenderPlan("line", java.util.Collections.emptyList(),
                java.util.Collections.emptyList(), timed, "kotoshi mo hayai ne to", null);
        RenderPlan sourcePlan = line.readingRenderPlan;

        LyricsSurfaceRowPlanner.coalesceTimedWordsForRuby(line);

        assertEquals(5, line.words.size());
        assertEquals("「今年", line.words.get(0).text);
        assertEquals(1000L, line.words.get(0).startMs);
        assertEquals(1200L, line.words.get(0).endMs);
        assertFalse(FuriganaText.hasRubyCrossingWordBoundaries(line));
        assertSame(sourcePlan, line.readingRenderPlan);
        assertEquals(6, line.readingRenderPlan.timedReadingUnits.size());
        assertEquals("kotoshi", line.readingRenderPlan.timedReadingUnits.get(0).text);
        assertEquals("1", line.readingRenderPlan.timedReadingUnits.get(1).spanId);
    }

    @Test
    public void numericPersonRubyCoalescingPreservesCanonicalText() {
        for (String digit : new String[]{"1", "2"}) {
            AppliedLine line = line(digit + "人");
            SyllableSegment number = word(digit, false);
            number.spanId = "number";
            SyllableSegment person = word("人", false);
            person.spanId = "person";
            person.boundaryAfter = true;
            person.boundaryProvenance = "testBoundary";
            line.words.add(number);
            line.words.add(person);
            line.japaneseReading = new SpicyJapaneseChineseProcessor.JapaneseReading(
                    line.text, "1".equals(digit) ? "hitori" : "futari",
                    java.util.Collections.singletonList(
                            new SpicyJapaneseChineseProcessor.FuriganaSegment(
                                    0, 2, "1".equals(digit) ? "ひとり" : "ふたり")));
            line.readingRenderPlan = new RenderPlan("line", java.util.Arrays.asList(
                    new CanonicalSpanMapping("number", new TextRange(0, 1)),
                    new CanonicalSpanMapping("person", new TextRange(1, 2))),
                    java.util.Collections.emptyList(), java.util.Collections.emptyList(),
                    line.japaneseReading.romaji, null);

            LyricsSurfaceRowPlanner.coalesceTimedWordsForRuby(line);

            assertEquals(1, line.words.size());
            assertEquals(digit + "人", line.words.get(0).text);
            assertEquals("number+person", line.words.get(0).spanId);
            assertTrue(line.words.get(0).boundaryAfter);
            assertEquals("testBoundary", line.words.get(0).boundaryProvenance);
            assertFalse(FuriganaText.hasRubyCrossingWordBoundaries(line));
        }
    }

    @Test
    public void japanesePerKanjiFuriganaCanStayWordLevel() {
        AppliedLine line = line("残念");
        line.words.add(word("残", false));
        line.words.add(word("念", true));
        java.util.ArrayList<SpicyJapaneseChineseProcessor.FuriganaSegment> segments = new java.util.ArrayList<>();
        segments.add(new SpicyJapaneseChineseProcessor.FuriganaSegment(0, 1, "ざん"));
        segments.add(new SpicyJapaneseChineseProcessor.FuriganaSegment(1, 2, "ねん"));
        line.japaneseReading = new SpicyJapaneseChineseProcessor.JapaneseReading(line.text, "zannen", segments);

        assertFalse(FuriganaText.hasRubyCrossingWordBoundaries(line));
    }

    @Test
    public void inferredPackedBoundaryDoesNotMakeRubyAttachToPreviousKana() {
        AppliedLine line = line("I let you go 君のためなら");
        line.words.add(word("I", false));
        line.words.add(word("let", false));
        line.words.add(word("you", false));
        line.words.add(word("go", false));
        line.words.add(word("君のた", true));
        line.words.add(word("めなら", true));
        line.japaneseReading = new SpicyJapaneseChineseProcessor.JapaneseReading(
                line.text, "I let you go kimi no tame nara", java.util.Collections.singletonList(
                new SpicyJapaneseChineseProcessor.FuriganaSegment(13, 14, "きみ")));

        assertFalse(FuriganaText.hasRubyCrossingWordBoundaries(line));
        assertTrue(FuriganaText.segmentStartsInWord(line.japaneseReading.furigana.get(0), 13, 16));
    }

    @Test
    public void rubyWordRangesUseStablePlanSpansForDuplicateText() {
        AppliedLine line = line("君君");
        SyllableSegment first = word("君", false);
        first.spanId = "left";
        SyllableSegment second = word("君", true);
        second.spanId = "right";
        line.words.add(first);
        line.words.add(second);
        line.readingRenderPlan = new RenderPlan("line", java.util.Arrays.asList(
                new CanonicalSpanMapping("left", new TextRange(0, 1)),
                new CanonicalSpanMapping("right", new TextRange(1, 2))),
                java.util.Collections.emptyList(), java.util.Collections.emptyList(), "", null);

        int[] right = FuriganaText.wordRange(line, second, 1, 0);
        assertEquals(1, right[0]);
        assertEquals(2, right[1]);
    }

    @Test
    public void liveCardScrollCanNormalizeRightAlignedLineWithoutMutatingSource() {
        AppliedLine source = line("right aligned lyric");
        source.oppositeAligned = true;
        LyricsSurfaceRowPlanner.SurfacePolicy policy = new LyricsSurfaceRowPlanner.SurfacePolicy(
                0.30f, false, false, "off", false,
                false, false, false, false,
                "Medium", "default", 0.68f, false, false, true);

        LyricsSurfaceRowPlanner.RowPlan plan = LyricsSurfaceRowPlanner.plan(source, document(source), policy);

        assertTrue(source.oppositeAligned);
        assertNotNull(plan.line);
        assertFalse(plan.line.oppositeAligned);
        assertEquals(source.text, plan.line.text);
        assertEquals(source.startMs, plan.line.startMs);
    }

    private static AppliedLine line(String text) {
        AppliedLine line = new AppliedLine();
        line.text = text;
        line.startMs = 1000L;
        line.endMs = 4000L;
        line.sourceLine = new LyricsLine();
        line.sourceLine.text = text;
        line.sourceLine.startMs = line.startMs;
        line.sourceLine.endMs = line.endMs;
        return line;
    }

    private static SyllableSegment word(String text, boolean partOfWord) {
        SyllableSegment seg = new SyllableSegment();
        seg.text = text;
        seg.partOfWord = partOfWord;
        return seg;
    }

    private static LyricsDocument document(AppliedLine line) {
        LyricsDocument doc = new LyricsDocument();
        doc.appliedLines.add(line);
        if (line != null && line.sourceLine != null) doc.lines.add(line.sourceLine);
        return doc;
    }
}
