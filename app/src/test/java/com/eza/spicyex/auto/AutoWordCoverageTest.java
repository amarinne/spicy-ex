package com.eza.spicyex.auto;

import com.eza.spicyex.lyrics.AppliedLine;
import com.eza.spicyex.lyrics.SyllableSegment;
import org.junit.Test;
import static org.junit.Assert.*;

public class AutoWordCoverageTest {
    @Test public void truncatedPublisherPacketFallsBackAndVisitsTrailingPages() {
        AppliedLine line = line("a".repeat(130));
        for (int i = 0; i < 128; i++) line.words.add(word(i, i + 1));
        line.romanizedText = "reading"; line.translatedText = "translation";
        AutoWordCoverage.fallbackIfIncomplete(line);
        assertTrue(line.syntheticWords); assertTrue(line.words.isEmpty());
        assertEquals(130, line.text.length());
        assertEquals(100, line.startMs); assertEquals(1400, line.endMs);
        assertEquals("reading", line.romanizedText); assertEquals("translation", line.translatedText);
        java.util.List<int[]> pages = AutoSurfaceFit.pages(line.text, (start, end) -> end - start <= 10);
        assertEquals(pages.size() - 1, AutoSurfaceFit.pageAt(pages, AutoLyricsView.sourceOffset(line, 1399)));
    }

    @Test public void oversizedTimingFallsBackEvenWithCompleteTextCoverage() {
        AppliedLine line = line("a".repeat(129));
        for (int i = 0; i < 129; i++) line.words.add(word(i, i + 1));
        assertFalse(AutoWordCoverage.withinLimit(129));
        assertTrue(AutoWordCoverage.withinLimit(128));
        AutoWordCoverage.fallbackIfIncomplete(line);
        assertTrue(line.syntheticWords); assertTrue(line.words.isEmpty());
    }

    @Test public void missingNonWhitespaceFallsBackButWhitespaceAndOverlapAreAllowed() {
        AppliedLine line = line("hello \u00a0world");
        line.words.add(word(0, 5)); line.words.add(word(7, 12));
        line.words.add(word(7, 10));
        assertTrue(AutoWordCoverage.complete(line));
        AutoWordCoverage.fallbackIfIncomplete(line);
        assertFalse(line.syntheticWords); assertEquals(3, line.words.size());
        line.text += "!";
        AutoWordCoverage.fallbackIfIncomplete(line);
        assertTrue(line.syntheticWords); assertTrue(line.words.isEmpty());
    }

    @Test public void codePointCoveragePreservesSupplementaryAndCombiningText() {
        AppliedLine line = line("🎵 e\u0301");
        line.words.add(word(0, 1)); line.words.add(word(2, 4));
        assertTrue(AutoWordCoverage.complete(line));
        line.words.set(1, word(2, 3));
        assertFalse(AutoWordCoverage.complete(line));
    }

    @Test public void invalidBoundsAndSyntheticPacketsCannotKeepPartialWords() {
        AppliedLine line = line("hello"); line.words.add(word(0, 6));
        AutoWordCoverage.fallbackIfIncomplete(line);
        assertTrue(line.syntheticWords); assertTrue(line.words.isEmpty());
        line.words.add(word(0, 5));
        AutoWordCoverage.fallbackIfIncomplete(line);
        assertTrue(line.words.isEmpty());
    }

    private static AppliedLine line(String text) {
        AppliedLine line = new AppliedLine(); line.text = text; line.startMs = 100; line.endMs = 1400;
        return line;
    }
    private static SyllableSegment word(int start, int end) {
        SyllableSegment word = new SyllableSegment(); word.canonicalStartCp = start; word.canonicalEndCp = end;
        word.startMs = 100; word.endMs = 1400; return word;
    }
}
