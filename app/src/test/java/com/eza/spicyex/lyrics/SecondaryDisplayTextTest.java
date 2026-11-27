package com.eza.spicyex.lyrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.eza.spicyex.lyrics.reading.ReadingModels.RenderPlan;
import com.eza.spicyex.lyrics.reading.ReadingPlanFactory;

import org.junit.Test;

import java.util.Collections;

public class SecondaryDisplayTextTest {
    @Test
    public void flattenIgnoresCasePunctuationSymbolsAndWhitespace() {
        assertEquals("helloworld", SecondaryDisplayText.flatten("Hello, world!"));
        assertEquals("helloworld", SecondaryDisplayText.flatten("hello — world..."));
        assertEquals("theymightfizzleoutsoon",
                SecondaryDisplayText.flatten("They might fizzle, out soon"));
        assertEquals("aintgonplaynice",
                SecondaryDisplayText.flatten("Ain’t gon' play nice!"));
        assertEquals("superhappynow",
                SecondaryDisplayText.flatten("（Super happy now）"));
        assertEquals("ok", SecondaryDisplayText.flatten("O.K.!?"));
        assertEquals("abc123", SecondaryDisplayText.flatten("ＡＢＣ, 1-2-3♪"));
        assertEquals("helloworld", SecondaryDisplayText.flatten("hello\u200B\u00A0world\uFEFF"));
    }

    @Test
    public void flattenPreservesDiacriticsAndDistinctScriptsAndProlongedSoundMark() {
        assertTrue(SecondaryDisplayText.sameText("caf\u0065\u0301", "caf\u00e9"));
        assertFalse(SecondaryDisplayText.sameText("café", "cafe"));
        assertFalse(SecondaryDisplayText.sameText("Mơ", "Mo"));
        assertFalse(SecondaryDisplayText.sameText("コーヒー", "コーヒ"));
        assertTrue(SecondaryDisplayText.sameText("おはよう〜", "おはよう"));
        assertTrue(SecondaryDisplayText.sameText("ありがとう！", "ありがとう"));
        assertFalse(SecondaryDisplayText.sameText("ありがとう", "arigatou"));
        assertFalse(SecondaryDisplayText.sameText("Show them it, girl", "Show dem it, girl"));
    }

    @Test
    public void sameTextHandlesBlankAndPunctuationOnlyInputs() {
        assertTrue(SecondaryDisplayText.sameText("", "   "));
        assertFalse(SecondaryDisplayText.sameText("Hello", ""));
        assertFalse(SecondaryDisplayText.sameText("", "..."));
        assertTrue(SecondaryDisplayText.sameText("...", "…"));
        assertTrue(SecondaryDisplayText.sameText("!!!", "?"));
        assertFalse(SecondaryDisplayText.sameText("Hello", "..."));
    }

    @Test
    public void readingDedupesAgainstMainLineAcrossPlanAndLegacySlot() {
        AppliedLine legacy = line("Hello, world!");
        legacy.romanizedText = "hello world";
        assertEquals("", LyricsRowViewFactory.displayReading(legacy));

        AppliedLine planned = line("(Oh, yeah!)");
        planned.readingRenderPlan = ReadingPlanFactory.lineFallback(
                planned.sourceLine, "oh yeah", "local");
        assertEquals("", LyricsRowViewFactory.displayReading(planned));

        AppliedLine distinct = line("ありがとう");
        distinct.readingRenderPlan = ReadingPlanFactory.lineFallback(
                distinct.sourceLine, "arigatou", "local");
        assertEquals("arigatou", LyricsRowViewFactory.displayReading(distinct));
    }

    @Test
    public void translationDedupesAgainstMainLineAndVisibleReadingOnly() {
        AppliedLine sameAsMain = line("Don't stop, baby!");
        sameAsMain.translatedText = "Dont stop baby";
        assertEquals("", LyricsRowViewFactory.displayTranslation(sameAsMain));

        AppliedLine sameAsReading = line("Стоп!");
        sameAsReading.romanizedText = "Stop!";
        sameAsReading.translatedText = "stop.";
        // Translation stays visible when reading is hidden.
        assertEquals("stop.", LyricsRowViewFactory.displayTranslation(sameAsReading, ""));
        // Translation dedupes when the same text is already shown as the reading line.
        assertEquals("", LyricsRowViewFactory.displayTranslation(sameAsReading, "Stop!"));

        AppliedLine distinctBoth = line("Стоп!");
        distinctBoth.romanizedText = "Stop!";
        distinctBoth.translatedText = "Halt!";
        assertEquals("Halt!", LyricsRowViewFactory.displayTranslation(distinctBoth, "Stop!"));
    }

    @Test
    public void visibleReadingRespectsJapaneseFuriganaOnlyMode() {
        AppliedLine jp = line("ストップ");
        jp.romanizedText = "stop";
        jp.translatedText = "Stop!";

        assertEquals("", SecondaryDisplayText.visibleReading(jp, true, "furigana_only"));
        assertEquals("Stop!", SecondaryDisplayText.translation(
                jp, SecondaryDisplayText.visibleReading(jp, true, "furigana_only")));

        assertEquals("stop", SecondaryDisplayText.visibleReading(jp, true, "romaji_only"));
        assertEquals("", SecondaryDisplayText.translation(
                jp, SecondaryDisplayText.visibleReading(jp, true, "romaji_only")));
    }

    @Test
    public void slicedLineComparesAgainstFullGradientSourceMainText() {
        AppliedLine full = line("Hello, world! This is a long line.");
        full.romanizedText = "hello world this is a long line";
        full.translatedText = "HELLO WORLD THIS IS A LONG LINE";

        AppliedLine page = new AppliedLine();
        page.text = "Hello, world!";
        page.romanizedText = full.romanizedText;
        page.translatedText = full.translatedText;
        page.gradientSource = full;

        assertEquals("", LyricsRowViewFactory.displayReading(page));
        assertEquals("", LyricsRowViewFactory.displayTranslation(page));

        AppliedLine distinctFull = line("ありがとう 世界");
        distinctFull.romanizedText = "arigatou sekai";
        distinctFull.translatedText = "Thank you world";
        AppliedLine distinctPage = new AppliedLine();
        distinctPage.text = "ありがとう";
        distinctPage.romanizedText = distinctFull.romanizedText;
        distinctPage.translatedText = distinctFull.translatedText;
        distinctPage.gradientSource = distinctFull;

        assertEquals("arigatou sekai", LyricsRowViewFactory.displayReading(distinctPage));
        assertEquals("Thank you world", LyricsRowViewFactory.displayTranslation(distinctPage));
    }

    @Test
    public void mountHeightEstimatorSkipsDedupedSecondaryRows() {
        LyricsRowMountController controller = new LyricsRowMountController(null, null, null, 40, 10, 10);

        AppliedLine dupBoth = line("Hello, world!");
        dupBoth.romanizedText = "hello world";
        dupBoth.translatedText = "HELLO WORLD";
        assertEquals(100, controller.rowHeightForIndex(
                Collections.singletonList(dupBoth), 0, 100, 30, true, true));

        AppliedLine dupTransVsReading = line("Стоп!");
        dupTransVsReading.romanizedText = "Stop!";
        dupTransVsReading.translatedText = "stop";
        assertEquals(130, controller.rowHeightForIndex(
                Collections.singletonList(dupTransVsReading), 0, 100, 30, true, true));
        assertEquals(130, controller.rowHeightForIndex(
                Collections.singletonList(dupTransVsReading), 0, 100, 30, false, true));

        AppliedLine distinct = line("ありがとう");
        distinct.readingRenderPlan = new RenderPlan("line", Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(), "arigatou", null);
        distinct.translatedText = "Thank you";
        assertEquals(160, controller.rowHeightForIndex(
                Collections.singletonList(distinct), 0, 100, 30, true, true));
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
}
