package com.eza.spicyex.lyrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Empty LRC timestamps end the previous line; only a real silence stays an interlude row. */
public class LrcShortGapCollapseTest {
    @Test
    public void breathBetweenLinesEndsThePreviousLineWithoutAnInterludeRow() {
        LyricsDocument doc = doc(vocal(1000, "first"), empty(20779), vocal(22279, "second"));

        LyricsParser.collapseShortLrcGaps(doc);

        assertEquals(2, doc.lines.size());
        assertEquals("first", doc.lines.get(0).text);
        assertEquals(20779L, doc.lines.get(0).endMs);
        assertEquals("second", doc.lines.get(1).text);
    }

    @Test
    public void longSilenceStaysAnInterludeAndStillEndsThePreviousLine() {
        LyricsDocument doc = doc(vocal(1000, "first"), empty(20000), vocal(20000
                + LyricTimeline.INTERLUDE_SHOW_THRESHOLD_MS, "second"));

        LyricsParser.collapseShortLrcGaps(doc);

        assertEquals(3, doc.lines.size());
        assertTrue(doc.lines.get(1).interlude);
        assertEquals(20000L, doc.lines.get(0).endMs);
    }

    @Test
    public void leadingInterludeBeforeAnyVocalIsKept() {
        LyricsDocument doc = doc(empty(0), vocal(500, "first"));

        LyricsParser.collapseShortLrcGaps(doc);

        assertEquals(2, doc.lines.size());
        assertTrue(doc.lines.get(0).interlude);
        assertFalse(doc.lines.get(1).interlude);
    }

    private static LyricsDocument doc(LyricsLine... lines) {
        LyricsDocument doc = new LyricsDocument();
        for (LyricsLine line : lines) doc.lines.add(line);
        return doc;
    }

    private static LyricsLine vocal(long startMs, String text) {
        LyricsLine line = new LyricsLine();
        line.startMs = startMs;
        line.text = text;
        return line;
    }

    private static LyricsLine empty(long startMs) {
        LyricsLine line = vocal(startMs, "♪");
        line.interlude = true;
        return line;
    }
}
