package com.eza.spicyex.lyrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RtlLyricsContractTest {
    @Test
    public void firstStrongCharacterDeterminesDirection() {
        assertTrue(SpicyTextDetection.isRtl("غصب عني"));
        assertTrue(SpicyTextDetection.isRtl("... ۱۲۳ عايزة"));
        assertTrue(SpicyTextDetection.isRtl("שיר בעברית"));
        assertFalse(SpicyTextDetection.isRtl("English ثم عربي"));
        assertFalse(SpicyTextDetection.isRtl("1234 ..."));
        assertTrue(SpicyTextDetection.hasStrongDirection("English ثم عربي"));
        assertFalse(SpicyTextDetection.hasStrongDirection("1234 ..."));
    }

    @Test
    public void rtlSyllablesKeepContextualShaping() {
        SyllableSegment arabic = new SyllableSegment();
        arabic.text = "عايزة";
        arabic.totalMs = 1800;
        assertFalse(LyricVisuals.shouldUseLetterAnimator(arabic));

        SyllableSegment mixed = new SyllableSegment();
        mixed.text = "DJ عايزة";
        mixed.totalMs = 1800;
        assertFalse(LyricVisuals.shouldUseLetterAnimator(mixed));

        SyllableSegment latin = new SyllableSegment();
        latin.text = "crazy";
        latin.totalMs = 1800;
        assertTrue(LyricVisuals.shouldUseLetterAnimator(latin));
    }

    @Test
    public void timedRtlUnitMarksTheWholeRendererRow() {
        AppliedLine line = new AppliedLine();
        line.text = "(۱۲۳)";
        SyllableSegment arabic = new SyllableSegment();
        arabic.text = "عايزة";
        line.words.add(arabic);

        assertTrue(LyricsRowViewFactory.isRtlLine(line));
    }

    @Test
    public void canonicalFirstStrongDirectionWinsForMixedTimedRows() {
        AppliedLine ltr = new AppliedLine();
        ltr.text = "English ثم عربي";
        SyllableSegment arabic = new SyllableSegment();
        arabic.text = "عربي";
        ltr.words.add(arabic);
        assertFalse(LyricsRowViewFactory.isRtlLine(ltr));

        AppliedLine rtl = new AppliedLine();
        rtl.text = "عربي then English";
        SyllableSegment english = new SyllableSegment();
        english.text = "English";
        rtl.words.add(english);
        assertTrue(LyricsRowViewFactory.isRtlLine(rtl));
    }

    @Test
    public void rtlOverflowStartsAtLogicalBeginningAndScrollsTowardPhysicalLeft() {
        assertEquals(-300, LyricsOverflowGeometry.childLeft(700, 1000, false, true));
        assertEquals(0, LyricsOverflowGeometry.childLeft(700, 1000, false, false));
        assertEquals(200, LyricsOverflowGeometry.childLeft(700, 500, false, true));
        assertEquals(0, LyricsOverflowGeometry.childLeft(700, 500, true, true));

        assertEquals(150f, LyricsOverflowGeometry.target(300f, 0.5f, true), 0.001f);
        assertEquals(-150f, LyricsOverflowGeometry.target(300f, 0.5f, false), 0.001f);
        assertEquals(300f, LyricsOverflowGeometry.clampTarget(400f, 300f, true), 0.001f);
        assertEquals(-300f, LyricsOverflowGeometry.clampTarget(-400f, 300f, false), 0.001f);
    }
}
