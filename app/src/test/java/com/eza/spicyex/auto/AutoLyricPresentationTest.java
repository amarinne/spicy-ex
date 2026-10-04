package com.eza.spicyex.auto;

import com.eza.spicyex.lyrics.AppliedLine;
import com.eza.spicyex.lyrics.LyricsDocument;
import org.junit.Test;
import static org.junit.Assert.*;

public class AutoLyricPresentationTest {
    @Test public void loadingKeepsNativeArtworkAndResolvedUnsyncedDocumentsUseNote() {
        assertEquals("native", AutoLyricPresentation.mode(null, -1));
        LyricsDocument doc = song(4000);
        doc.type = "Static";
        assertEquals(-1, AutoLyricPresentation.rowAt(doc, 1500));
        assertEquals("note", AutoLyricPresentation.mode(doc, -1));
        doc.type = "Line"; doc.appliedLines.clear();
        doc.appliedLines.add(row("", 0, 10000, true));
        assertEquals(-1, AutoLyricPresentation.rowAt(doc, 1500));
        assertEquals("note", AutoLyricPresentation.mode(doc, -1));
    }

    @Test public void nativeArtworkCoversLoadingIntroAndOutroButNotMiddleGaps() {
        assertTrue(AutoLyricPresentation.nativeArtwork(false, false, Long.MAX_VALUE, 0, 0, false));
        assertTrue(AutoLyricPresentation.nativeArtwork(true, true, 4000, 20000, 3499, false));
        assertFalse(AutoLyricPresentation.nativeArtwork(true, true, 4000, 20000, 3500, false));
        assertFalse(AutoLyricPresentation.nativeArtwork(true, true, 4000, 20000, 12000, false));
        assertFalse(AutoLyricPresentation.nativeArtwork(true, true, 4000, 20000, 19999, false));
        assertTrue(AutoLyricPresentation.nativeArtwork(true, true, 4000, 20000, 20700, false));
        // Seeking back into vocals restores takeover. Stress still exercises the surface.
        assertFalse(AutoLyricPresentation.nativeArtwork(true, true, 4000, 20000, 10000, false));
        assertFalse(AutoLyricPresentation.nativeArtwork(false, false, Long.MAX_VALUE, 0, 0, true));
        assertFalse(AutoLyricPresentation.nativeArtwork(true, false, Long.MAX_VALUE, 0, 10000, false));
    }

    @Test public void shortGapHoldsPreviousLineUntilExactNextStartWithoutChangingSourceTiming() {
        LyricsDocument doc = song(4999);
        assertEquals(0, AutoLyricPresentation.rowAt(doc, 2000));
        assertFalse(AutoLyricPresentation.vocalActiveAt(doc, 2000));
        assertTrue(AutoLyricPresentation.vocalActiveAt(doc, 1500));
        assertEquals(0, AutoLyricPresentation.rowAt(doc, 6998));
        assertEquals("lyric", AutoLyricPresentation.mode(doc, 0));
        assertEquals(2, AutoLyricPresentation.rowAt(doc, 6999));
        assertEquals(2000, doc.appliedLines.get(0).endMs);
        assertEquals(0, AutoLyricPresentation.rowAt(doc, 1500));
    }

    @Test public void fiveSecondGapKeepsDotsAndTrailingInstrumentalRestoresArtwork() {
        LyricsDocument doc = song(5000);
        assertEquals(1, AutoLyricPresentation.rowAt(doc, 2000));
        assertEquals("dots", AutoLyricPresentation.mode(doc, 1));
        assertEquals(2, AutoLyricPresentation.rowAt(doc, 7000));
        doc.appliedLines.add(row("", 8000, 18000, true));
        assertEquals("native", AutoLyricPresentation.mode(doc, AutoLyricPresentation.rowAt(doc, 9000)));
    }

    @Test public void introPreloadsFirstVocalWithoutChangingTiming() {
        LyricsDocument doc = new LyricsDocument(); doc.type = "Line";
        doc.appliedLines.add(row("", 0, 4000, true));
        doc.appliedLines.add(row("vocal", 4000, 5000, false));
        assertEquals("lyric", AutoLyricPresentation.mode(doc, AutoLyricPresentation.rowAt(doc, 2000)));
        doc.appliedLines.get(0).endMs = 6000;
        doc.appliedLines.get(1).startMs = 6000; doc.appliedLines.get(1).endMs = 7000;
        assertEquals("lyric", AutoLyricPresentation.mode(doc, AutoLyricPresentation.rowAt(doc, 2000)));
    }

    @Test public void uncoveredMiddleGapKeepsVisibleNoteFallback() {
        LyricsDocument doc = song(8000);
        doc.appliedLines.get(1).endMs = 5000;
        int index = AutoLyricPresentation.rowAt(doc, 6000);
        assertEquals(-1, index);
        assertEquals("note", AutoLyricPresentation.mode(doc, index));
        assertFalse(AutoLyricPresentation.nativeArtwork(true, true, 1000, 11000, 6000, false));
    }

    @Test public void outroHoldsLastVocalUntilExactReleaseBoundary() {
        LyricsDocument doc = song(5000);
        doc.appliedLines.add(row("", 8000, 18000, true));
        assertEquals(2, AutoLyricPresentation.rowAt(doc, 8000));
        assertEquals(2, AutoLyricPresentation.rowAt(doc, 8699));
        assertEquals(3, AutoLyricPresentation.rowAt(doc, 8700));
        assertFalse(AutoLyricPresentation.nativeArtwork(true, true, 1000, 8000, 8699, false));
        assertTrue(AutoLyricPresentation.nativeArtwork(true, true, 1000, 8000, 8700, false));
        assertEquals(8000, doc.appliedLines.get(2).endMs);
    }

    @Test public void immediateVocalsSkipOpeningButLaterSeeksUseNormalEntrance() {
        assertFalse(AutoLyricPresentation.nativeArtwork(true, true, 1000, 8000, 0, false));
        assertTrue(AutoLyricPresentation.immediateEntrance(true, 0, 0));
        assertTrue(AutoLyricPresentation.immediateEntrance(true, 1000, 1500));
        assertFalse(AutoLyricPresentation.immediateEntrance(true, 1000, 1501));
        assertFalse(AutoLyricPresentation.immediateEntrance(true, 1001, 0));
        assertFalse(AutoLyricPresentation.immediateEntrance(false, 0, 0));
        assertTrue(AutoLyricPresentation.nativeArtwork(true, true, 1001, 8000, 500, false));
        assertFalse(AutoLyricPresentation.nativeArtwork(true, true, 1001, 8000, 501, false));
    }

    private static LyricsDocument song(long gap) {
        LyricsDocument doc = new LyricsDocument(); doc.type = "Line";
        doc.appliedLines.add(row("previous", 1000, 2000, false));
        doc.appliedLines.add(row("", 2000, 2000 + gap, true));
        doc.appliedLines.add(row("next", 2000 + gap, 3000 + gap, false));
        return doc;
    }

    private static AppliedLine row(String text, long start, long end, boolean dots) {
        AppliedLine line = new AppliedLine(); line.text = text;
        line.startMs = start; line.endMs = end; line.dotLine = dots;
        return line;
    }
}
