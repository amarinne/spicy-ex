package com.eza.spicyex.lyrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.eza.spicyex.lyrics.reading.ReadingModels.CanonicalLine;
import com.eza.spicyex.lyrics.reading.ReadingModels.RenderPlan;
import com.eza.spicyex.lyrics.reading.ReadingModels.TextRange;
import com.eza.spicyex.lyrics.reading.ReadingModels.TimedReadingUnit;
import com.eza.spicyex.lyrics.reading.ReadingPlanFactory;
import com.eza.spicyex.lyrics.reading.SyllableCanonicalizer;

public class TimedTextRowProjectionTest {
    @Test
    public void exactReconstructionRejectsDuplicatedTimedSuffix() {
        assertFalse(TimedTextRowProjection.exactlyReconstructs(
                Arrays.asList("najě yolgiga sigun gorirô", "yolgigasigungorirô"),
                "najě yolgiga sigun gorirô"));
        assertTrue(TimedTextRowProjection.exactlyReconstructs(
                Arrays.asList("najě yolgiga", " sigun gorirô"),
                "najě yolgiga sigun gorirô"));
    }

    @Test
    public void exactReconstructionGuardIsLanguageIndependent() {
        assertExactReading("kimi no na wa", "kimi no", " na wa");
        assertExactReading("nǐ hǎo shìjiè", "nǐ hǎo", " shìjiè");
        assertExactReading("privet mir", "privet", " mir");
        assertExactReading("sawasdee khrap", "sawasdee", " khrap");

        assertDuplicatedReading("kimi no na wa", "kimi no na wa", "nawa");
        assertDuplicatedReading("nǐ hǎo shìjiè", "nǐ hǎo shìjiè", "shìjiè");
        assertDuplicatedReading("privet mir", "privet mir", "mir");
        assertDuplicatedReading("sawasdee khrap", "sawasdee khrap", "khrap");
    }

    @Test
    public void plannedLeadingSpacesBecomeSingleSeams() {
        List<TimedTextRowProjection.Chunk> chunks = TimedTextRowProjection.project(
                Arrays.asList("bai", " bai", " sekai"), "bai bai sekai");

        assertChunk(chunks.get(0), "bai", true);
        assertChunk(chunks.get(1), "bai", true);
        assertChunk(chunks.get(2), "sekai", false);
    }

    @Test
    public void sourceProjectionKeepsProviderSplitWordClosed() {
        List<TimedTextRowProjection.Chunk> chunks = TimedTextRowProjection.project(
                Arrays.asList("Trust", "me,", "Rea", "dy"), "Trust me, Ready");

        assertChunk(chunks.get(0), "Trust", true);
        assertChunk(chunks.get(1), "me,", true);
        assertChunk(chunks.get(2), "Rea", false);
        assertChunk(chunks.get(3), "dy", false);
    }

    @Test
    public void providerAttachedFragmentsFormOneMotionGroup() {
        List<SyllableSegment> segments = new ArrayList<>();
        segments.add(timedSegment("Trust", false, 6518, 6942));
        segments.add(timedSegment("me,", false, 6942, 7305));
        segments.add(timedSegment("Rea", true, 7902, 8291));
        segments.add(timedSegment("dy", false, 8291, 8834));
        CanonicalLine canonical = SyllableCanonicalizer.canonicalize(
                "ready", "", segments);
        AppliedLine line = new AppliedLine();
        line.words.addAll(segments);

        assertEquals("Trust me, Ready", canonical.text);
        assertEquals(0, TimedWordGrouping.groupEnd(line, 0));
        assertEquals(1, TimedWordGrouping.groupEnd(line, 1));
        assertEquals(3, TimedWordGrouping.groupEnd(line, 2));
        assertFalse(TimedWordGrouping.isGrouped(line, 0));
        assertTrue(TimedWordGrouping.isGrouped(line, 2));
        assertTrue(TimedWordGrouping.isGrouped(line, 3));
        assertEquals(7902L, TimedWordGrouping.startMs(line, 2));
        assertEquals(8834L, TimedWordGrouping.endMs(line, 2));
        assertEquals(2, TimedWordGrouping.focusIndex(line, 2, 3, 8100));
        assertEquals(3, TimedWordGrouping.focusIndex(line, 2, 3, 8400));
        assertEquals(0.95f, LyricsAnimationApplier.wordMotionScale(
                false, false, false, 0f), 0.0001f);
        assertEquals(1f, LyricsAnimationApplier.wordMotionScale(
                false, false, true, 1f), 0.0001f);
        assertTrue(LyricsAnimationApplier.wordMotionScale(
                false, true, false, 0.5f) > 1f);
        assertTrue(LyricsAnimationApplier.wordMotionY(
                false, true, false, 0.5f) < 0f);
    }

    @Test
    public void groupedMotionKeepsCompletedFragmentsStillAndSeamsClosed() {
        assertEquals(1f, LyricsAnimationApplier.groupedWrapperScale(true), 0.0001f);
        assertEquals(1f, LyricsAnimationApplier.groupedWrapperScale(false), 0.0001f);
        assertEquals(0f, LyricsAnimationApplier.groupedWrapperY(true), 0.0001f);
        assertEquals(1f, LyricsAnimationApplier.groupedLocalScale(
                false, false, true, false, 0.5f), 0.0001f);
        assertEquals(0f, LyricsAnimationApplier.groupedLocalY(
                false, false, true, false, 0.5f), 0.0001f);
        assertTrue(LyricsAnimationApplier.groupedLocalScale(
                true, false, true, false, 0.5f) > 1f);
        assertEquals(1f, LyricsAnimationApplier.groupedLocalScale(
                true, true, true, false, 0.5f), 0.0001f);
        assertTrue(LyricsAnimationApplier.groupedLocalY(
                true, true, true, false, 0.5f) < 0f);
    }

    @Test
    public void disabledWordBounceKeepsMotionTargetsNeutral() {
        assertEquals(1f, LyricsAnimationApplier.inactiveWordScale(false, false), 0.0001f);
        assertEquals(0.95f, LyricsAnimationApplier.inactiveWordScale(true, false), 0.0001f);
        assertEquals(1f, LyricsAnimationApplier.inactiveWordScale(true, true), 0.0001f);
        assertEquals(1f, LyricsAnimationApplier.letterMotionScale(
                false, 0.5f, 1f), 0.0001f);
        assertEquals(0f, LyricsAnimationApplier.letterMotionY(
                false, 0.5f, 1f), 0.0001f);
        assertTrue(LyricsAnimationApplier.letterMotionScale(true, 0.5f, 1f) > 1f);
        assertTrue(LyricsAnimationApplier.letterMotionY(true, 0.5f, 1f) < 0f);
    }

    @Test
    public void bounceStylesKeepDistinctMotionContracts() {
        assertTrue(LyricsAnimationApplier.wordMotionScale(
                false, true, false, 0.5f) > 1f);
        assertEquals(1f, LyricsAnimationApplier.wordMotionScale(
                true, true, false, 0.5f), 0.0001f);
        assertTrue(LyricsAnimationApplier.wordMotionY(
                true, true, false, 0.5f) < 0f);
    }

    @Test
    public void newlyMountedWordMotionStartsAtCurrentPlaybackTarget() {
        SyllableSegment segment = timedSegment("wide", false, 1000, 2000);

        assertEquals(1.037f,
                LyricsSyllableViewState.stepWordScale(segment, 1.037f, 1f / 60f),
                0.0001f);
        assertEquals(-0.012f,
                LyricsSyllableViewState.stepWordY(segment, -0.012f, 1f / 60f),
                0.0001f);
    }

    @Test
    public void initializedWordMotionStillSpringsTowardLaterTargets() {
        SyllableSegment segment = timedSegment("wide", false, 1000, 2000);
        LyricsSyllableViewState.stepWordScale(segment, 0.95f, 1f / 60f);
        LyricsSyllableViewState.stepWordY(segment, 0.01f, 1f / 60f);

        float scale = LyricsSyllableViewState.stepWordScale(segment, 1.05f, 1f / 60f);
        float y = LyricsSyllableViewState.stepWordY(segment, -0.016f, 1f / 60f);

        assertTrue(scale > 0.95f && scale < 1.05f);
        assertTrue(y < 0.01f && y > -0.016f);
    }

    @Test
    public void newlyMountedLetterMotionStartsAtCurrentPlaybackTarget() {
        AnimatedLetterState letter = new AnimatedLetterState();

        assertEquals(1.037f,
                LyricsSyllableViewState.stepLetterScale(letter, 1.037f, 1f / 60f),
                0.0001f);
        assertEquals(-0.012f,
                LyricsSyllableViewState.stepLetterY(letter, -0.012f, 1f / 60f),
                0.0001f);
    }

    @Test
    public void newlyMountedGroupedWordStartsAtCurrentPlaybackTarget() {
        SyllableSegment segment = timedSegment("wide", true, 1000, 2000);

        assertEquals(1.037f,
                LyricsSyllableViewState.stepLocalWordScale(segment, 1.037f, 1f / 60f),
                0.0001f);
        assertEquals(-0.012f,
                LyricsSyllableViewState.stepLocalWordY(segment, -0.012f, 1f / 60f),
                0.0001f);
    }

    @Test
    public void syntheticTimingSectionsRemainIndependentMotionGroups() {
        AppliedLine line = new AppliedLine();
        line.syntheticWords = true;
        line.words.add(timedSegment("電車が", true, 1000, 1400));
        line.words.add(timedSegment("ホームに", true, 1400, 2000));

        assertEquals(0, TimedWordGrouping.groupEnd(line, 0));
        assertEquals(1, TimedWordGrouping.groupEnd(line, 1));
        assertFalse(TimedWordGrouping.isGrouped(line, 0));
    }

    @Test
    public void providerJapaneseWordFlagsPreventWholeLineMotionGroup() {
        AppliedLine line = new AppliedLine();
        line.words.add(timedSegment("導", true, 1000, 1200));
        line.words.add(timedSegment("いた", false, 1200, 1600));
        line.words.add(timedSegment("鼓", true, 1600, 1800));
        line.words.add(timedSegment("動", false, 1800, 2100));
        for (SyllableSegment segment : line.words) segment.boundaryAfter = false;

        assertEquals(1, TimedWordGrouping.groupEnd(line, 0));
        assertEquals(3, TimedWordGrouping.groupEnd(line, 2));
    }

    @Test
    public void japaneseReadingGroupsKeepClauseWrappableDespiteProviderFlags() {
        AppliedLine line = new AppliedLine();
        String[] texts = {"愚", "鈍", "で", "偶", "像", "で", "不", "毛", "に", "見え", "ます", "か"};
        String[] groups = {"jp-0", "jp-0", "jp-1", "jp-2", "jp-2", "jp-3",
                "jp-4", "jp-4", "jp-5", "jp-6", "jp-6", "jp-7"};
        ArrayList<TimedReadingUnit> timed = new ArrayList<>();
        for (int index = 0; index < texts.length; index++) {
            line.words.add(timedSegment(texts[index], true, index * 100L, index * 100L + 100L));
            line.words.get(index).spanId = String.valueOf(index);
            timed.add(new TimedReadingUnit(String.valueOf(index), new TextRange(index, index + 1),
                    index == 0 ? "gudon" : "", groups[index]));
        }
        line.readingRenderPlan = new RenderPlan("line", java.util.Collections.emptyList(),
                java.util.Collections.emptyList(), timed,
                "gudon de guuzou de fumou ni miemasu ka", null);

        assertEquals(1, TimedWordGrouping.groupEnd(line, 0));
        assertEquals(2, TimedWordGrouping.groupEnd(line, 2));
        assertEquals(4, TimedWordGrouping.groupEnd(line, 3));
        assertEquals(7, TimedWordGrouping.groupEnd(line, 6));
        assertEquals(10, TimedWordGrouping.groupEnd(line, 9));
        assertEquals(11, TimedWordGrouping.groupEnd(line, 11));
    }

    @Test
    public void timedWordMotionHeightMatchesDirectBaselineGeometry() {
        assertEquals(83, TimedWordMotionLayout.baselineHeight(83, 62, 21));
        assertEquals(89, TimedWordMotionLayout.baselineHeight(83, 66, 23));
        assertEquals(83, TimedWordMotionLayout.baselineHeight(83, -1, -1));
    }

    @Test
    public void edgeWordZoomPivotsInward() {
        assertEquals(0f, LyricsSyllableViewState.horizontalMotionPivot(0, 200, 200, 1000), 0.001f);
        assertEquals(200f, LyricsSyllableViewState.horizontalMotionPivot(800, 1000, 200, 1000), 0.001f);
        assertEquals(100f, LyricsSyllableViewState.horizontalMotionPivot(300, 500, 200, 1000), 0.001f);
        assertEquals(45f, LyricsSyllableViewState.horizontalMotionPivot(
                300, 500, 200, 1000, 45f), 0.001f);
    }

    @Test
    public void syntheticGroupsRecoverAuthoritativeSpaces() {
        List<TimedTextRowProjection.Chunk> chunks = TimedTextRowProjection.project(
                Arrays.asList("de mo", "yuzurenai", "mono no"),
                "de mo yuzurenai mono no");

        assertChunk(chunks.get(0), "de mo", true);
        assertChunk(chunks.get(1), "yuzurenai", true);
        assertChunk(chunks.get(2), "mono no", false);
    }

    @Test
    public void mergedTimedReadingKeepsPlanWhitespace() {
        AppliedLine line = new AppliedLine();
        ArrayList<TimedReadingUnit> timed = new ArrayList<>();
        timed.add(new TimedReadingUnit("0", new TextRange(0, 5), "Found", "jp-0"));
        timed.add(new TimedReadingUnit("1", new TextRange(5, 8), " kako ga", "jp-1"));
        timed.add(new TimedReadingUnit("2", new TextRange(8, 9), " michibiku", "jp-2"));
        timed.add(new TimedReadingUnit("3", new TextRange(9, 10), "", "jp-2"));
        timed.add(new TimedReadingUnit("4", new TextRange(10, 11), " taita", "jp-3"));
        line.readingRenderPlan = new RenderPlan("line", java.util.Collections.emptyList(),
                java.util.Collections.emptyList(), timed,
                "Found kako ga michibiku taita", null);
        Map<String, TimedReadingUnit> byId = new HashMap<>();
        for (TimedReadingUnit unit : timed) byId.put(unit.spanId, unit);

        SyllableSegment found = segment("Found", false);
        found.spanId = "0";
        SyllableSegment past = segment("過去が", true);
        past.spanId = "1";
        SyllableSegment led = segment("導いた", false);
        led.spanId = "2+3+4";
        LyricsRowViewFactory.Options options = new LyricsRowViewFactory.Options();
        List<String> raw = Arrays.asList(
                LyricsRowViewFactory.romanizedWordText(line, found, 0, byId, options, null),
                LyricsRowViewFactory.romanizedWordText(line, past, 1, byId, options, null),
                LyricsRowViewFactory.romanizedWordText(line, led, 2, byId, options, null));

        assertEquals(Arrays.asList("Found", " kako ga", " michibiku taita"), raw);
        assertEquals("Found kako ga michibiku taita",
                render(TimedTextRowProjection.project(raw,
                        LyricsRowViewFactory.displayReading(line))));
    }

    @Test
    public void blankTimingContinuationKeepsNextWordSeam() {
        List<TimedTextRowProjection.Chunk> chunks = TimedTextRowProjection.project(
                Arrays.asList("datte", "", "iu"), "datte iu");

        assertChunk(chunks.get(0), "datte", true);
        assertChunk(chunks.get(1), "", false);
        assertChunk(chunks.get(2), "iu", false);
    }

    @Test
    public void blankTimedOwnersDoNotFallBackToIndependentJapaneseReadings() {
        AppliedLine line = new AppliedLine();
        ArrayList<TimedReadingUnit> timed = new ArrayList<>();
        timed.add(new TimedReadingUnit("2", new TextRange(7, 8), " yumeutsutsu", "jp-2"));
        timed.add(new TimedReadingUnit("3", new TextRange(8, 10), "", "jp-2"));
        timed.add(new TimedReadingUnit("4", new TextRange(10, 11), "", "jp-2"));
        timed.add(new TimedReadingUnit("5", new TextRange(11, 12), " no", "jp-3"));
        timed.add(new TimedReadingUnit("6", new TextRange(12, 13), " hannin", "jp-4"));
        timed.add(new TimedReadingUnit("7", new TextRange(13, 14), "", "jp-4"));
        line.readingRenderPlan = new RenderPlan("line", java.util.Collections.emptyList(),
                java.util.Collections.emptyList(), timed,
                "gomakasu no? yumeutsutsu no hannin", null);
        Map<String, TimedReadingUnit> byId = new HashMap<>();
        for (TimedReadingUnit unit : timed) byId.put(unit.spanId, unit);
        LyricsRowViewFactory.Options options = new LyricsRowViewFactory.Options();

        SyllableSegment utsu = segment("うつ", true);
        utsu.spanId = "3";
        utsu.romanizedText = "utsu";
        SyllableSegment tsu = segment("つ", true);
        tsu.spanId = "4";
        tsu.romanizedText = "tsu";
        SyllableSegment hito = segment("人", false);
        hito.spanId = "7";
        hito.romanizedText = "hito";
        LyricsRowViewFactory.RomanizedWordProvider fallback =
                (ignoredLine, segment, ignoredText) -> segment.romanizedText;

        assertEquals("", LyricsRowViewFactory.romanizedWordText(
                line, utsu, 3, byId, options, fallback));
        assertEquals("", LyricsRowViewFactory.romanizedWordText(
                line, tsu, 4, byId, options, fallback));
        assertEquals("", LyricsRowViewFactory.romanizedWordText(
                line, hito, 7, byId, options, fallback));
    }

    @Test
    public void morphologicalContinuationDoesNotInventSpace() {
        List<TimedTextRowProjection.Chunk> chunks = TimedTextRowProjection.project(
                Arrays.asList("da", "tte", " kimi ga"), "datte kimi ga");

        assertChunk(chunks.get(0), "da", false);
        assertChunk(chunks.get(1), "tte", true);
        assertChunk(chunks.get(2), "kimi ga", false);
    }

    @Test
    public void realJapaneseTimedSpansReconstructAuthoritativeRomaji() {
        assertTimedProjection("でも譲れないもの", "でも", "譲れ", "ない", "もの");
        assertTimedProjection("バイバイ世界", "バイ", "バイ", "世界");
        assertTimedProjection("握ったこの手は離さない", "握っ", "た", "この", "手", "は", "離さ", "ない");
        assertTimedProjection("だって君が", "だ", "って", "君", "が");
        assertTimedProjection("Found 過去が導いた", "Found", "過去が", "導", "い", "た");
        assertTimedProjection("欲望が鼓動になって", "欲", "望", "が", "鼓", "動", "に", "成っ", "て");
        assertTimedProjection("嗚呼時過ぎる消えない Adore",
                "嗚呼", "時", "過ぎる", "消えない", "A", "dore");
    }

    @Test
    public void unalignedFallbackUsesOnlyExplicitEdgeWhitespace() {
        List<TimedTextRowProjection.Chunk> chunks = TimedTextRowProjection.project(
                Arrays.asList("foo ", "bar", " baz"), "different text");

        assertChunk(chunks.get(0), "foo", true);
        assertChunk(chunks.get(1), "bar", true);
        assertChunk(chunks.get(2), "baz", false);
    }

    private static void assertTimedProjection(String source, String... spans) {
        LyricsLine line = new LyricsLine();
        line.text = source;
        for (int index = 0; index < spans.length; index++) {
            SyllableSegment segment = segment(spans[index], true);
            segment.spanId = String.valueOf(index);
            line.syllables.add(segment);
        }
        SpicyJapaneseChineseProcessor.JapaneseReading reading =
                SpicyJapaneseChineseProcessor.analyzeJapaneseLine(source, null);
        List<String> projected = SpicyJapaneseChineseProcessor.romanizeJapaneseSyllables(
                reading, Arrays.asList(spans));
        RenderPlan plan = ReadingPlanFactory.japanese(line, reading);
        assertEquals("reading=" + reading.romaji + " projected=" + projected,
                spans.length, plan.timedReadingUnits.size());
        ArrayList<String> raw = new ArrayList<>();
        for (TimedReadingUnit unit : plan.timedReadingUnits) raw.add(unit.text);

        assertEquals(reading.romaji,
                render(TimedTextRowProjection.project(raw, reading.romaji)));
    }

    private static SyllableSegment segment(String text, boolean providerPartOfWord) {
        SyllableSegment segment = new SyllableSegment();
        segment.text = text;
        segment.sourceText = text;
        segment.providerPartOfWord = providerPartOfWord;
        return segment;
    }

    private static SyllableSegment timedSegment(String text, boolean providerPartOfWord,
                                                long startMs, long endMs) {
        SyllableSegment segment = segment(text, providerPartOfWord);
        segment.startMs = startMs;
        segment.endMs = endMs;
        segment.totalMs = endMs - startMs;
        return segment;
    }

    private static String render(List<TimedTextRowProjection.Chunk> chunks) {
        StringBuilder rendered = new StringBuilder();
        for (TimedTextRowProjection.Chunk chunk : chunks) {
            if (chunk.text.isEmpty()) continue;
            rendered.append(chunk.text);
            if (chunk.spaceAfter) rendered.append(' ');
        }
        return rendered.toString();
    }

    private static void assertChunk(TimedTextRowProjection.Chunk chunk, String text,
                                    boolean spaceAfter) {
        assertEquals(text, chunk.text);
        assertEquals(spaceAfter, chunk.spaceAfter);
    }

    private static void assertExactReading(String authoritative, String... chunks) {
        assertTrue(authoritative, TimedTextRowProjection.exactlyReconstructs(
                Arrays.asList(chunks), authoritative));
    }

    private static void assertDuplicatedReading(String authoritative, String... chunks) {
        assertFalse(authoritative, TimedTextRowProjection.exactlyReconstructs(
                Arrays.asList(chunks), authoritative));
    }
}
