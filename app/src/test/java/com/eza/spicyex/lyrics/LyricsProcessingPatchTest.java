package com.eza.spicyex.lyrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LyricsProcessingPatchTest {

    @Test
    public void applyToUpdatesDocumentFlagsAndLineFields() {
        LyricsDocument doc = document(line("original", "old romanized", "old translated"));
        doc.romanizationPending = true;
        doc.translationPending = true;
        doc.processingPending = true;

        LyricsProcessingPatch patch = new LyricsProcessingPatch();
        patch.includesRomanization = true;
        patch.includesTranslation = true;
        patch.changed = 2;

        LyricsProcessingPatch.LinePatch linePatch = new LyricsProcessingPatch.LinePatch(0);
        linePatch.setRomanizedText("new romanized");
        linePatch.setTranslatedText("new translated");
        patch.addLinePatch(linePatch);

        patch.applyTo(doc);

        assertFalse(doc.romanizationPending);
        assertFalse(doc.translationPending);
        assertFalse(doc.processingPending);
        assertTrue(doc.includesRomanization);
        assertTrue(doc.includesTranslation);
        assertEquals(2, patch.changed);
        assertEquals("new romanized", doc.lines.get(0).romanizedText);
        assertEquals("new translated", doc.lines.get(0).translatedText);
    }

    @Test
    public void fromLineCopiesSegmentRomanization() {
        LyricsLine source = line("kana", "romaji", "translated");
        source.chineseMode = "pinyin";
        source.syllables.add(segment("ka", "ka"));
        source.syllables.add(segment("na", "na"));
        LyricsDocument doc = document(line("kana", "", ""));
        doc.lines.get(0).syllables.add(segment("ka", ""));
        doc.lines.get(0).syllables.add(segment("na", ""));

        LyricsProcessingPatch patch = new LyricsProcessingPatch();
        patch.addLinePatch(LyricsProcessingPatch.fromLine(0, source, true, true));
        patch.applyTo(doc);

        LyricsLine target = doc.lines.get(0);
        assertEquals("romaji", target.romanizedText);
        assertEquals("translated", target.translatedText);
        assertEquals("pinyin", target.chineseMode);
        assertEquals("ka", target.syllables.get(0).romanizedText);
        assertEquals("na", target.syllables.get(1).romanizedText);
    }

    @Test
    public void outOfRangeLinePatchIsNoOp() {
        LyricsDocument doc = document(line("a", "old", ""));
        LyricsProcessingPatch patch = new LyricsProcessingPatch();
        LyricsProcessingPatch.LinePatch linePatch = new LyricsProcessingPatch.LinePatch(9);
        linePatch.setRomanizedText("new");
        patch.addLinePatch(linePatch);

        patch.applyTo(doc);

        assertEquals("old", doc.lines.get(0).romanizedText);
    }

    private static LyricsDocument document(LyricsLine... lines) {
        LyricsDocument doc = new LyricsDocument();
        for (LyricsLine line : lines) doc.lines.add(line);
        return doc;
    }

    private static LyricsLine line(String text, String romanized, String translated) {
        LyricsLine line = new LyricsLine();
        line.text = text;
        line.romanizedText = romanized;
        line.translatedText = translated;
        return line;
    }

    private static SyllableSegment segment(String text, String romanized) {
        SyllableSegment segment = new SyllableSegment();
        segment.text = text;
        segment.romanizedText = romanized;
        return segment;
    }
}
