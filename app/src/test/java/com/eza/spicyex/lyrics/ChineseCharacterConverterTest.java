package com.eza.spicyex.lyrics;

import com.eza.spicyex.lyrics.language.ChineseCharacterConverter;
import com.eza.spicyex.lyrics.language.ChineseCharacterForm;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ChineseCharacterConverterTest {
    @Test public void convertsCommonSimplifiedAndTraditionalForms() {
        assertEquals("頭髮", ChineseCharacterConverter.convert("头发", ChineseCharacterForm.TRADITIONAL));
        assertEquals("头发", ChineseCharacterConverter.convert("頭髮", ChineseCharacterForm.SIMPLIFIED));
    }

    @Test public void originalLeavesTextUntouched() {
        assertEquals("漢語", ChineseCharacterConverter.convert("漢語", ChineseCharacterForm.ORIGINAL));
    }

    @Test public void convertsCharactersMissingFromTheSmallLyricTable() {
        assertEquals("静忆温请别离谁没伤骗谎愿",
                ChineseCharacterConverter.convert("靜憶溫請別離誰沒傷騙謊願", ChineseCharacterForm.SIMPLIFIED));
        assertEquals("靜憶溫請別離誰沒傷騙謊願",
                ChineseCharacterConverter.convert("静忆温请别离谁没伤骗谎愿", ChineseCharacterForm.TRADITIONAL));
    }

    @Test public void choosesAmbiguousFormsWithPhraseContext() {
        assertEquals("頭髮已經變白，發展卻還在繼續",
                ChineseCharacterConverter.convert("头发已经变白，发展却还在继续", ChineseCharacterForm.TRADITIONAL));
        assertEquals("皇后在後臺，裡面乾淨，乾杯，幹部",
                ChineseCharacterConverter.convert("皇后在后台，里面干净，干杯，干部", ChineseCharacterForm.TRADITIONAL));
        assertEquals("乾坤干燥干部干杯头发发展",
                ChineseCharacterConverter.convert("乾坤乾燥幹部乾杯頭髮發展", ChineseCharacterForm.SIMPLIFIED));
    }

    @Test public void usesTaiwanGlyphFormsWithoutRegionalVocabularyChanges() {
        assertEquals("颱風、軟件、視頻、出租車",
                ChineseCharacterConverter.convert("台风、软件、视频、出租车", ChineseCharacterForm.TRADITIONAL));
        // Segmentation must precede all conversion stages, as in desktop s2tw.
        assertEquals("一週後周末，鐘錶與鬧鐘，抬頭看臺風",
                ChineseCharacterConverter.convert("一周后周末，钟表与闹钟，抬头看台风", ChineseCharacterForm.TRADITIONAL));
    }

    @Test public void retainsAllReverseVariantsAndSupplementaryCodePoints() {
        assertEquals("发发里里台台",
                ChineseCharacterConverter.convert("發髮裡裏臺颱", ChineseCharacterForm.SIMPLIFIED));
        assertEquals("𠁞儣𠌥俓🙂A漢語",
                ChineseCharacterConverter.convert("𠀾𠆲𠆿𠇹🙂A汉语", ChineseCharacterForm.TRADITIONAL));
        assertEquals("𠀾儣𠌥俓🙂A汉语",
                ChineseCharacterConverter.convert("𠁞儣𠌥俓🙂A漢語", ChineseCharacterForm.SIMPLIFIED));
    }

    @Test public void normalizesCompatibilityIdeographsBeforeConversion() {
        assertEquals("車龍你", ChineseCharacterConverter.convert("車龍你", ChineseCharacterForm.TRADITIONAL));
        assertEquals("车龙你", ChineseCharacterConverter.convert("車龍你", ChineseCharacterForm.SIMPLIFIED));
    }

    @Test public void leavesUnmatchedIdeographicDescriptionSequencesAtomic() {
        assertEquals("⿰汉马漢馬", ChineseCharacterConverter.convert("⿰汉马汉马", ChineseCharacterForm.TRADITIONAL));
        assertEquals("⿲汉⿰马龙发漢馬",
                ChineseCharacterConverter.convert("⿲汉⿰马龙发汉马", ChineseCharacterForm.TRADITIONAL));
    }

    @Test public void projectsSplitTimedPhraseWithWholeLineContext() {
        ChineseCharacterConverter.Projection result = ChineseCharacterConverter.project(
                "🙂头发和发展", ChineseCharacterForm.TRADITIONAL);
        assertEquals("🙂頭髮和發展", result.text);
        assertEquals(6, result.sourceCodePointCount());
        assertEquals("🙂", result.slice(0, 1));
        assertEquals("頭", result.slice(1, 2));
        assertEquals("髮", result.slice(2, 3));
        assertEquals("發", result.slice(4, 5));
        assertEquals(result.text, result.slice(0, 3) + result.slice(3, 6));
        for (int i = 0; i <= 6; i++) assertEquals(i, result.mapBoundary(i));
    }

    @Test public void nullAndEmptyInputsDoNotLoadOrAlterText() {
        assertEquals("", ChineseCharacterConverter.convert(null, ChineseCharacterForm.TRADITIONAL));
        assertEquals("", ChineseCharacterConverter.convert("", ChineseCharacterForm.SIMPLIFIED));
        assertEquals("漢語", ChineseCharacterConverter.convert("漢語", null));
    }
}
