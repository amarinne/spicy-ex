package com.eza.spicyex.lyrics;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Collections;

import org.junit.Test;

public class LyricsSecondaryRowUpdaterTest {
    @Test
    public void refreshDecisionCopiesJapaneseReadingAndRemountsWhenFuriganaAppears() {
        AppliedLine row = row("今年");
        SpicyJapaneseChineseProcessor.JapaneseReading reading = reading(row.text, "ことし");
        row.sourceLine.japaneseReading = reading;

        LyricsSecondaryRowUpdater.RefreshDecision decision = LyricsSecondaryRowUpdater.decideRefresh(
                row, "", "", true, false, "furigana_only", true);

        assertTrue(decision.japaneseReadingChanged);
        assertTrue(decision.remountForFurigana);

        row.japaneseReading = row.sourceLine.japaneseReading;
        assertSame(reading, row.japaneseReading);
    }

    @Test
    public void refreshDecisionDoesNotRemountUnmountedRowWhenFuriganaAppears() {
        AppliedLine row = row("今年");
        row.sourceLine.japaneseReading = reading(row.text, "ことし");

        LyricsSecondaryRowUpdater.RefreshDecision decision = LyricsSecondaryRowUpdater.decideRefresh(
                row, "", "", true, false, "furigana_only", false);

        assertTrue(decision.japaneseReadingChanged);
        assertFalse(decision.remountForFurigana);
    }

    private static AppliedLine row(String text) {
        AppliedLine row = new AppliedLine();
        row.text = text;
        row.romanizedText = "";
        row.translatedText = "";
        row.sourceLine = new LyricsLine();
        row.sourceLine.text = text;
        return row;
    }

    private static SpicyJapaneseChineseProcessor.JapaneseReading reading(String text, String ruby) {
        return new SpicyJapaneseChineseProcessor.JapaneseReading(
                text,
                "kotoshi",
                Collections.singletonList(new SpicyJapaneseChineseProcessor.FuriganaSegment(0, text.length(), ruby)));
    }
}
