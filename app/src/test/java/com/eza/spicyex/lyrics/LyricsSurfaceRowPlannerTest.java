package com.eza.spicyex.lyrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LyricsSurfaceRowPlannerTest {
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

        LyricsSurfaceRowPlanner.plan(fullscreen, doc, fullscreenPolicy, (line, seg, fullText) -> "x");
        LyricsSurfaceRowPlanner.plan(liveCard, liveDoc, livePolicy, (line, seg, fullText) -> "x");

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
    public void attachedTransliterationUsesSharedProviderOnlyWhenAligned() {
        AppliedLine line = line("hello world");
        line.romanizedText = "heh-loh world";
        LyricsDocument doc = document(line);
        LyricsRowViewFactory.RomanizedWordProvider provider = (row, seg, fullText) -> "x";
        LyricsSurfaceRowPlanner.SurfacePolicy policy = new LyricsSurfaceRowPlanner.SurfacePolicy(
                1f, true, false, "off", true,
                false, false, false, false,
                "Medium", "default", 1f, false, true);

        LyricsSurfaceRowPlanner.RowPlan plan = LyricsSurfaceRowPlanner.plan(line, doc, policy, provider);

        assertTrue(plan.options.attachTransliterationToWords);
        assertSame(provider, plan.romanizedWordProvider);
        assertFalse(plan.options.documentText.isEmpty());
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

        LyricsSurfaceRowPlanner.RowPlan plan = LyricsSurfaceRowPlanner.plan(line, doc, policy,
                (row, seg, fullText) -> "x");

        assertFalse(plan.options.attachTransliterationToWords);
        assertNull(plan.romanizedWordProvider);
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

        LyricsSurfaceRowPlanner.RowPlan plan = LyricsSurfaceRowPlanner.plan(line, document(line), policy,
                (row, seg, fullText) -> "x");

        assertFalse(line.syntheticWords);
        assertTrue(line.words.isEmpty());
        assertTrue(plan.options.showJapaneseFurigana);
        assertNotNull(plan.options);
    }

    @Test
    public void liveCardScrollCanNormalizeRightAlignedLineWithoutMutatingSource() {
        AppliedLine source = line("right aligned lyric");
        source.oppositeAligned = true;
        LyricsSurfaceRowPlanner.SurfacePolicy policy = new LyricsSurfaceRowPlanner.SurfacePolicy(
                0.30f, false, false, "off", false,
                false, false, false, false,
                "Medium", "default", 0.68f, false, false, true);

        LyricsSurfaceRowPlanner.RowPlan plan = LyricsSurfaceRowPlanner.plan(source, document(source), policy, null);

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

    private static LyricsDocument document(AppliedLine line) {
        LyricsDocument doc = new LyricsDocument();
        doc.appliedLines.add(line);
        if (line != null && line.sourceLine != null) doc.lines.add(line.sourceLine);
        return doc;
    }
}
