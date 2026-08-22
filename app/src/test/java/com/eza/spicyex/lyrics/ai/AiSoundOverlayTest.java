package com.eza.spicyex.lyrics.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.eza.spicyex.lyrics.LyricsLine;
import com.eza.spicyex.lyrics.SyllableSegment;
import com.eza.spicyex.lyrics.reading.ReadingModels.RenderPlan;
import com.eza.spicyex.lyrics.reading.ReadingModels.TimedReadingUnit;
import com.eza.spicyex.lyrics.reading.ReadingPlanFactory;
import com.eza.spicyex.lyrics.session.SoundEntry;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * L1, pinned.
 *
 * <p>The rule these protect is the one that is easy to lose by accident: a row the deterministic
 * reading pipeline covered is not touched by AI, no matter how confident the model was. Years of
 * curated reading rules, furigana, and word-level timing outrank a line of generated text, and the
 * failure mode if this regresses is silent — the reading still renders, it is just worse, and
 * nothing reports it.
 */
public class AiSoundOverlayTest {

    /** A row with genuine word-level reading work: two timed spans from the local pipeline. */
    private static RenderPlan realPlan() {
        LyricsLine line = new LyricsLine();
        line.text = "\u4eca\u65e5 \u306f";
        line.startMs = 0L;
        line.endMs = 2000L;
        line.syllables = new ArrayList<>(Arrays.asList(
                syllable("\u4eca\u65e5", "kyou", 0L, 1000L),
                syllable("\u306f", "wa", 1000L, 2000L)));
        return ReadingPlanFactory.timedLegacy(line, "kyou wa", "Japanese");
    }

    private static SyllableSegment syllable(String text, String romanized, long start, long end) {
        SyllableSegment segment = new SyllableSegment();
        segment.text = text;
        segment.sourceText = text;
        segment.romanizedText = romanized;
        segment.startMs = start;
        segment.endMs = end;
        return segment;
    }

    /** What a Google whole-line fallback leaves behind: display text, no span structure. */
    private static SoundEntry googleGapFill(String rowId, String text) {
        return SoundEntry.line(rowId, text, "rr");
    }

    /** A cached/compatibility whole-line fallback that acquired a synthetic timing owner. */
    private static RenderPlan timedRawFallback(LyricsLine line) {
        RenderPlan fallback = ReadingPlanFactory.lineFallback(
                line, line.text, "remoteFallback");
        assertNotNull(fallback);
        return new RenderPlan(fallback.lineId, fallback.sourceUnits, fallback.readingUnits,
                Collections.singletonList(new TimedReadingUnit("line",
                        fallback.readingUnits.get(0).canonicalRange, line.text,
                        "line-fallback")), fallback.joinedDisplayText, fallback.translation);
    }

    /** Exact shape observed from Thai provider compatibility data: timed, but all passthrough. */
    private static RenderPlan timedPassthroughThaiPlan() {
        LyricsLine line = new LyricsLine();
        line.text = "ก็ไม่รู้";
        line.startMs = 0L;
        line.endMs = 2_000L;
        line.syllables = new ArrayList<>(Arrays.asList(
                syllable("ก็", "ก็", 0L, 500L),
                syllable("ไม่", "ไม่", 500L, 1_250L),
                syllable("รู้", "รู้", 1_250L, 2_000L)));
        return ReadingPlanFactory.timedLegacy(line, line.text, "LocalScript");
    }

    // --- coverage detection --------------------------------------------------

    @Test
    public void atimedPlanCountsAsDeterministicCoverage() {
        RenderPlan plan = realPlan();
        assertNotNull("the fixture must produce a real plan, or this test proves nothing", plan);
        assertFalse(plan.timedReadingUnits.isEmpty());
        assertTrue(AiSoundOverlay.hasDeterministicCoverage(
                SoundEntry.plan("r0#aaaa", "romaji", plan)));
    }

    @Test
    public void perSpanReadingsCountAsDeterministicCoverage() {
        SoundEntry entry = new SoundEntry("r0#aaaa", "", "pinyin", null,
                Collections.singletonList(new SoundEntry.SpanReading("r0#aaaa/s0#x", 0, "ni")));
        assertTrue(AiSoundOverlay.hasDeterministicCoverage(entry));
    }

    @Test
    public void awholeLineFallbackIsNotDeterministicCoverage() {
        assertFalse(AiSoundOverlay.hasDeterministicCoverage(googleGapFill("r0#aaaa", "annyeong")));
        assertFalse(AiSoundOverlay.hasDeterministicCoverage(null));
    }

    @Test
    public void asynthesizedWholeLineFallbackIsNotDeterministicCoverage() {
        LyricsLine line = new LyricsLine();
        line.text = "ก็ไม่รู้";
        line.startMs = 0L;
        line.endMs = 2_000L;
        RenderPlan fallback = timedRawFallback(line);

        assertFalse(AiSoundOverlay.hasDeterministicCoverage(
                SoundEntry.plan("r0#aaaa", "rtgs", fallback)));
    }

    @Test
    public void atimedAllPassthroughPlanIsNotDeterministicCoverage() {
        RenderPlan plan = timedPassthroughThaiPlan();
        assertNotNull(plan);
        assertFalse(plan.timedReadingUnits.isEmpty());
        assertFalse(AiSoundOverlay.hasDeterministicCoverage(
                SoundEntry.plan("r0#aaaa", "rtgs", plan)));
    }

    // --- the L1 rule ---------------------------------------------------------

    @Test
    public void arowWithARealReadingPlanIsUntouchedByAnAiOverlayForThatSameRow() {
        SoundEntry deterministic = SoundEntry.plan("r0#aaaa", "romaji", realPlan());
        List<SoundEntry> overlay =
                AiSoundOverlay.entriesOf(
                        Collections.singletonList(new AiResponseItem("r0#aaaa", "kyoo wa")), "romaji");

        List<SoundEntry> composed = AiSoundOverlay.compose(
                Collections.singletonList(deterministic), overlay);

        assertEquals(1, composed.size());
        assertSame("the deterministic entry must survive by identity, not by value",
                deterministic, composed.get(0));
        assertNotNull(composed.get(0).renderPlan);
    }

    @Test
    public void aiReplacesGoogleInTheSameLineLevelSlot() {
        SoundEntry google = googleGapFill("r0#aaaa", "chan");
        List<SoundEntry> overlay = AiSoundOverlay.entriesOf(
                Collections.singletonList(new AiResponseItem("r0#aaaa", "chan rak")), "rtgs");

        List<SoundEntry> composed =
                AiSoundOverlay.compose(Collections.singletonList(google), overlay);

        assertEquals(1, composed.size());
        assertEquals("chan rak", composed.get(0).displayText);
        assertEquals("rtgs", composed.get(0).mode);
    }

    @Test
    public void aiReplacesAPlanBackedRawThaiFallback() {
        LyricsLine line = new LyricsLine();
        line.text = "ก็ไม่รู้";
        line.startMs = 0L;
        line.endMs = 2_000L;
        SoundEntry raw = SoundEntry.plan("r0#aaaa", "rtgs",
                timedRawFallback(line));
        List<SoundEntry> overlay = AiSoundOverlay.entriesOf(
                Collections.singletonList(new AiResponseItem("r0#aaaa", "ko mai ru")), "Latin");

        List<SoundEntry> composed = AiSoundOverlay.compose(
                Collections.singletonList(raw), overlay);

        assertEquals(1, composed.size());
        assertEquals("ko mai ru", composed.get(0).displayText);
        assertEquals(null, composed.get(0).renderPlan);
    }

    @Test
    public void aiReplacesTheObservedTimedPassthroughThaiPlan() {
        SoundEntry raw = SoundEntry.plan("r0#aaaa", "rtgs", timedPassthroughThaiPlan());
        List<SoundEntry> overlay = AiSoundOverlay.entriesOf(
                Collections.singletonList(new AiResponseItem("r0#aaaa", "ko mai ru")), "Latin");

        List<SoundEntry> composed = AiSoundOverlay.compose(
                Collections.singletonList(raw), overlay);

        assertEquals("ko mai ru", composed.get(0).displayText);
        assertEquals(null, composed.get(0).renderPlan);
    }

    @Test
    public void arowTheBaseNeverCoveredIsFilledOutright() {
        List<SoundEntry> overlay = AiSoundOverlay.entriesOf(
                Collections.singletonList(new AiResponseItem("r1#bbbb", "sawatdee")), "rtgs");

        List<SoundEntry> composed = AiSoundOverlay.compose(
                Collections.singletonList(googleGapFill("r0#aaaa", "chan")), overlay);

        assertEquals(2, composed.size());
        assertEquals("r0#aaaa", composed.get(0).rowId);
        assertEquals("r1#bbbb", composed.get(1).rowId);
        assertEquals("sawatdee", composed.get(1).displayText);
    }

    @Test
    public void amixedDocumentKeepsEveryCoveredRowAndFillsEveryGap() {
        SoundEntry covered = SoundEntry.plan("r0#aaaa", "romaji", realPlan());
        SoundEntry gap = googleGapFill("r1#bbbb", "chan");
        List<SoundEntry> overlay = AiSoundOverlay.entriesOf(Arrays.asList(
                new AiResponseItem("r0#aaaa", "kyoo wa"),
                new AiResponseItem("r1#bbbb", "chan rak")), "rtgs");

        List<SoundEntry> composed = AiSoundOverlay.compose(Arrays.asList(covered, gap), overlay);

        assertEquals(2, composed.size());
        assertSame(covered, composed.get(0));
        assertEquals("chan rak", composed.get(1).displayText);
    }

    // --- line-level by construction -------------------------------------------

    @Test
    public void anOverlayEntryCarriesNoSpanStructureAtAll() {
        SoundEntry entry = AiSoundOverlay.entriesOf(
                Collections.singletonList(new AiResponseItem("r0#aaaa", "kyoo wa")), "romaji")
                .get(0);
        assertEquals("kyoo wa", entry.displayText);
        assertTrue("AI output must never acquire spans", entry.spanReadings.isEmpty());
        assertEquals("AI output must never acquire a plan", null, entry.renderPlan);
        assertEquals("AI output must never acquire a Japanese reading", null,
                entry.japaneseReading);
    }

    @Test
    public void anOverlayEntryIsNeverSplitOnWhitespaceToGuessBoundaries() {
        // Two whitespace-separated words stay one line-level reading, not two span readings.
        SoundEntry entry = AiSoundOverlay.entriesOf(
                Collections.singletonList(new AiResponseItem("r0#aaaa", "kyoo wa ii tenki")),
                "romaji").get(0);
        assertEquals("kyoo wa ii tenki", entry.displayText);
        assertTrue(entry.spanReadings.isEmpty());
    }

    @Test
    public void ablankReadingIsNotAnOverlayAtAll() {
        assertTrue(AiSoundOverlay.entriesOf(
                Collections.singletonList(new AiResponseItem("r0#aaaa", "   ")), "romaji").isEmpty());
        assertTrue(AiSoundOverlay.entriesOf(null, "romaji").isEmpty());
    }

    @Test
    public void composingWithNothingOnEitherSideIsSafe() {
        assertTrue(AiSoundOverlay.compose(null, null).isEmpty());
        SoundEntry google = googleGapFill("r0#aaaa", "chan");
        assertEquals(1, AiSoundOverlay.compose(Collections.singletonList(google), null).size());
        assertEquals(1, AiSoundOverlay.compose(null,
                Collections.singletonList(googleGapFill("r0#aaaa", "x"))).size());
    }
}
