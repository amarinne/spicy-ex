package com.eza.spicyex.lyrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import static org.junit.Assert.assertSame;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/** Regression coverage for the timing/row-planning bugs fixed when extracting LyricTimeline. */
public class LyricTimelineTest {

    private static LyricsLine vocal(String text, long startMs, long endMs) {
        LyricsLine line = new LyricsLine();
        line.text = text;
        line.startMs = startMs;
        line.endMs = endMs;
        return line;
    }

    private static LyricsLine marker(long startMs) {
        LyricsLine line = new LyricsLine();
        line.interlude = true;
        line.startMs = startMs;
        return line;
    }

    private static LyricsDocument lineDoc(LyricsLine... lines) {
        LyricsDocument doc = new LyricsDocument();
        doc.type = "Line";
        for (LyricsLine line : lines) doc.lines.add(line);
        return doc;
    }

    // --- fillMissingEndTimes ---

    @Test
    public void interludeMarkerSpansWholeInstrumental() {
        // Native-DB style: marker at 10s, next vocal at 30s. The marker must extend to the next
        // vocal start, not be truncated to 3.5s (the old behavior left ~16.5s of dead air).
        List<LyricsLine> lines = new ArrayList<>();
        lines.add(vocal("a", 0, 10_000));
        lines.add(marker(10_000));
        lines.add(vocal("b", 30_000, 33_000));
        LyricTimeline.fillMissingEndTimes(lines);
        assertEquals(30_000, lines.get(1).endMs);
    }

    @Test
    public void vocalWithSmallGapExtendsToNextStart() {
        List<LyricsLine> lines = new ArrayList<>();
        lines.add(vocal("a", 0, 0));
        lines.add(vocal("b", 2_000, 0));
        LyricTimeline.fillMissingEndTimes(lines);
        assertEquals(2_000, lines.get(0).endMs);
    }

    @Test
    public void vocalBeforeLargeGapKeepsDefaultDuration() {
        // The vocal highlight must not swallow an instrumental break; the dot row covers it.
        List<LyricsLine> lines = new ArrayList<>();
        lines.add(vocal("a", 0, 0));
        lines.add(vocal("b", 20_000, 0));
        LyricTimeline.fillMissingEndTimes(lines);
        assertEquals(LyricTimeline.DEFAULT_LINE_DURATION_MS, lines.get(0).endMs);
    }

    @Test
    public void realEndTimesAreNeverOverwritten() {
        List<LyricsLine> lines = new ArrayList<>();
        lines.add(vocal("a", 0, 1_234));
        lines.add(vocal("b", 2_000, 0));
        LyricTimeline.fillMissingEndTimes(lines);
        assertEquals(1_234, lines.get(0).endMs);
    }

    @Test
    public void trailingLineGetsDefaultDuration() {
        List<LyricsLine> lines = new ArrayList<>();
        lines.add(vocal("a", 5_000, 0));
        LyricTimeline.fillMissingEndTimes(lines);
        assertEquals(5_000 + LyricTimeline.DEFAULT_LINE_DURATION_MS, lines.get(0).endMs);
    }

    // --- applySyncedRows / row planning ---

    @Test
    public void introDotRowCreatedBeforeLateFirstVocal() {
        LyricsDocument doc = lineDoc(vocal("a", 8_000, 11_000));
        LyricTimeline.applySyncedRows(doc);
        assertEquals(2, doc.appliedLines.size());
        assertTrue(doc.appliedLines.get(0).dotLine);
        assertEquals(0, doc.appliedLines.get(0).startMs);
        assertEquals(8_000, doc.appliedLines.get(0).endMs);
    }

    @Test
    public void noIntroDotRowForEarlyFirstVocal() {
        LyricsDocument doc = lineDoc(vocal("a", 1_000, 4_000));
        LyricTimeline.applySyncedRows(doc);
        assertEquals(1, doc.appliedLines.size());
        assertFalse(doc.appliedLines.get(0).dotLine);
    }

    @Test
    public void explicitLeadingMarkerSuppressesSynthesizedIntroDots() {
        // With markers now spanning the full instrumental, synthesizing an intro dot row on top
        // of an explicit leading marker would double the dots.
        LyricsLine lead = marker(0);
        lead.endMs = 8_000;
        LyricsDocument doc = lineDoc(lead, vocal("a", 8_000, 11_000));
        LyricTimeline.applySyncedRows(doc);
        int dotRows = 0;
        for (AppliedLine row : doc.appliedLines) if (row.dotLine) dotRows++;
        assertEquals(1, dotRows);
    }

    @Test
    public void gapDotRowSynthesizedBetweenDistantVocals() {
        LyricsDocument doc = lineDoc(vocal("a", 0, 4_000), vocal("b", 20_000, 23_000));
        LyricTimeline.applySyncedRows(doc);
        assertEquals(3, doc.appliedLines.size());
        AppliedLine dots = doc.appliedLines.get(1);
        assertTrue(dots.dotLine);
        assertEquals(4_000, dots.startMs);
        assertEquals(20_000, dots.endMs);
    }

    @Test
    public void explicitMarkerSuppressesSynthesizedGapDots() {
        LyricsLine mid = marker(4_000);
        mid.endMs = 20_000;
        LyricsDocument doc = lineDoc(vocal("a", 0, 4_000), mid, vocal("b", 20_000, 23_000));
        LyricTimeline.applySyncedRows(doc);
        int dotRows = 0;
        for (AppliedLine row : doc.appliedLines) if (row.dotLine) dotRows++;
        assertEquals(1, dotRows);
    }

    @Test
    public void vocalRowActiveWindowExtendsAcrossShortGap() {
        // 2s gap: the row's active window carries to the next start so the highlight and
        // follow-scroll never drop to "no active row" between lines...
        LyricsDocument doc = lineDoc(vocal("a", 0, 4_000), vocal("b", 6_000, 9_000));
        LyricTimeline.applySyncedRows(doc);
        assertEquals(6_000, doc.appliedLines.get(0).endMs);
        // ...but the karaoke fill still runs to the line's REAL end.
        assertEquals(4_000, LyricTimeline.fillEndMs(doc.appliedLines.get(0)));
    }

    @Test
    public void vocalRowDoesNotExtendAcrossLargeGap() {
        LyricsDocument doc = lineDoc(vocal("a", 0, 4_000), vocal("b", 20_000, 23_000));
        LyricTimeline.applySyncedRows(doc);
        assertEquals(4_000, doc.appliedLines.get(0).endMs);
    }

    @Test
    public void dotWordTimingsAreMonotonic() {
        AppliedLine dots = LyricTimeline.createAppliedDotRow(0, 12_000, false);
        assertEquals(3, dots.words.size());
        long previous = 0;
        for (SyllableSegment word : dots.words) {
            assertTrue(word.startMs >= previous);
            assertTrue(word.endMs >= word.startMs);
            previous = word.endMs;
        }
    }

    // --- active-row selection ---

    @Test
    public void backgroundAndLeadRowsAreBothActive() {
        LyricsLine lead = vocal("lead", 0, 4_000);
        BackgroundLine bg = new BackgroundLine();
        bg.text = "bg";
        bg.startMs = 1_000;
        bg.endMs = 5_000;
        lead.backgroundLines.add(bg);
        LyricsDocument doc = new LyricsDocument();
        doc.type = "Syllable";
        doc.lines.add(lead);
        LyricTimeline.applySyncedRows(doc);
        assertEquals(2, doc.appliedLines.size());
        assertTrue(LyricTimeline.isRowActiveAt(doc.appliedLines.get(0), 2_000));
        assertTrue(LyricTimeline.isRowActiveAt(doc.appliedLines.get(1), 2_000));
        // The primary row (scroll anchor / published state) is the lead vocal, not the bg row.
        assertEquals(0, LyricTimeline.findPrimaryActiveRow(doc.appliedLines, 2_000));
    }

    @Test
    public void primaryActivePrefersMostRecentlyStartedLead() {
        // Overlap from window extension: at t=6.5s both rows are active; the newer line wins.
        LyricsDocument doc = lineDoc(vocal("a", 0, 4_000), vocal("b", 6_000, 9_000));
        LyricTimeline.applySyncedRows(doc);
        assertEquals(0, LyricTimeline.findPrimaryActiveRow(doc.appliedLines, 5_000));
        assertEquals(1, LyricTimeline.findPrimaryActiveRow(doc.appliedLines, 6_500));
    }

    @Test
    public void dotRowIsPrimaryDuringInterlude() {
        LyricsDocument doc = lineDoc(vocal("a", 0, 4_000), vocal("b", 20_000, 23_000));
        LyricTimeline.applySyncedRows(doc);
        int primary = LyricTimeline.findPrimaryActiveRow(doc.appliedLines, 10_000);
        assertTrue(doc.appliedLines.get(primary).dotLine);
    }

    // --- static rebalance ---

    @Test
    public void staticTimingsSpreadAcrossTrackDuration() {
        LyricsDocument doc = new LyricsDocument();
        doc.type = "Static";
        doc.durationMs = 200_000;
        for (int i = 0; i < 50; i++) doc.lines.add(vocal("line " + i, 0, 0));
        LyricTimeline.rebalanceStaticTimings(doc);
        assertEquals(4_000, doc.lines.get(1).startMs - doc.lines.get(0).startMs);
        assertEquals(200_000, doc.lines.get(49).endMs);
    }

    @Test
    public void rebalanceIgnoresSyncedDocuments() {
        LyricsDocument doc = lineDoc(vocal("a", 1_000, 2_000));
        LyricTimeline.rebalanceStaticTimings(doc);
        assertEquals(1_000, doc.lines.get(0).startMs);
        assertEquals(2_000, doc.lines.get(0).endMs);
    }

    @Test
    public void appliedRowsAbsorbDerivedTextWithoutReplanning() {
        LyricsDocument doc = new LyricsDocument();
        LyricsLine line = new LyricsLine();
        line.text = "ichi";
        line.startMs = 0L;
        line.endMs = 1000L;
        doc.lines.add(line);
        LyricTimeline.applySyncedRows(doc);
        AppliedLine row = doc.appliedLines.get(0);
        assertEquals("", row.romanizedText);

        line.romanizedText = "ichi-r";
        line.translatedText = "one";

        assertTrue(LyricTimeline.refreshAppliedDerivedText(doc));
        assertSame("row identity must survive so timing and view state are kept",
                row, doc.appliedLines.get(0));
        assertEquals("ichi-r", row.romanizedText);
        assertEquals("one", row.translatedText);
        assertFalse("a second pass over unchanged text reports nothing",
                LyricTimeline.refreshAppliedDerivedText(doc));
    }
}
