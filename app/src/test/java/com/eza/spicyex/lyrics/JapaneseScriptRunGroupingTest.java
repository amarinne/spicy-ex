package com.eza.spicyex.lyrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class JapaneseScriptRunGroupingTest {

    @Test
    public void katakanaRunStaysOneWord() {
        // サヨナラ must not be split across a wrap.
        assertEquals(list("サヨナラ"), texts("サヨナラ"));
    }

    @Test
    public void katakanaWithProlongedMarkStaysTogether() {
        assertEquals(list("ギター"), texts("ギター"));
        assertEquals(list("コーヒー"), texts("コーヒー"));
    }

    @Test
    public void kanjiCompoundStaysOneWord() {
        assertEquals(list("約束"), texts("約束"));
        assertEquals(list("幸福感"), texts("幸福感"));
    }

    @Test
    public void okuriganaStaysWithItsKanji() {
        assertEquals(list("歌うよ"), texts("歌うよ"));
        List<String> parts = texts("新しい世界");
        assertTrue(parts.contains("新しい"));
        assertTrue(parts.contains("世界"));
    }

    @Test
    public void scriptChangeIsABreakOpportunity() {
        // 君 / と / サヨナラ - the boundary between scripts is where a break belongs.
        List<String> parts = texts("君とサヨナラ");
        assertTrue(parts.size() > 1);
        assertTrue(parts.contains("サヨナラ"));
        assertFalse(parts.contains("君とサヨナラ"));
    }

    @Test
    public void shortReduplicatedOnomatopoeiaStaysWhole() {
        // Four characters is still short enough to keep on one line outright.
        assertEquals(list("ドキドキ"), texts("ドキドキ"));
    }

    @Test
    public void longReduplicatedOnomatopoeiaSplitsAtItsSeams() {
        // Long runs have to be breakable; for reduplication the repeat seams are real morpheme
        // boundaries, unlike anywhere else inside the run.
        // Split on the shortest repeating unit: every seam is a morpheme boundary, so offering
        // all of them gives the wrapper the most room while never cutting a morpheme in half.
        assertEquals(list("ドキ", "ドキ", "ドキ"), texts("ドキドキドキ"));
        assertEquals(list("キラ", "キラ", "キラ", "キラ"), texts("キラキラキラキラ"));
    }

    @Test
    public void longKatakanaLoanwordStaysBreakable() {
        // A long, non-reduplicated loanword has to be able to break somewhere, or the planner
        // gives up on the whole line and wraps greedily - which breaks it worse.
        List<String> parts = texts("コミュニケーション");
        assertTrue(parts.size() > 1);
    }

    @Test
    public void smallKanaAndProlongedMarkNeverStartAGroup() {
        for (List<String> parts : new List[]{texts("コミュニケーション"), texts("シャッター")}) {
            for (String part : parts) {
                int first = part.codePointAt(0);
                assertFalse("group may not start with " + part,
                        JapaneseScriptRunGrouping.isKanaTail(first));
            }
        }
    }

    @Test
    public void closingPunctuationNeverStartsAGroup() {
        for (String part : texts("さよなら。またね！")) {
            assertFalse(JapaneseScriptRunGrouping.isClosingPunctuation(part.codePointAt(0)));
        }
    }

    @Test
    public void iterationMarkStaysWithItsKanji() {
        assertTrue(texts("人々").contains("人々"));
    }

    @Test
    public void whitespaceSeparatesGroups() {
        List<String> parts = texts("君と サヨナラ");
        assertTrue(parts.contains("サヨナラ"));
        for (String part : parts) assertFalse(part.contains(" "));
    }

    @Test
    public void emptyInputProducesNothing() {
        assertTrue(JapaneseScriptRunGrouping.forText(null).isEmpty());
        assertTrue(JapaneseScriptRunGrouping.forText("").isEmpty());
        assertTrue(JapaneseScriptRunGrouping.forText("   ").isEmpty());
    }

    @Test
    public void groupsAreOrderedNonOverlappingAndInBounds() {
        String text = "夜空にドキドキするコミュニケーション、君と歌う。";
        int previousEnd = 0;
        for (DisplayLayoutGroup group : JapaneseScriptRunGrouping.forText(text)) {
            assertTrue(group.start >= previousEnd);
            assertTrue(group.end > group.start);
            assertTrue(group.end <= text.length());
            assertTrue(group.keepTogether);
            previousEnd = group.end;
        }
    }

    @Test
    public void reduplicationUnitRejectsNonRepeats() {
        assertEquals(-1, JapaneseScriptRunGrouping.reduplicationUnit("コミュニケ", 0, 5));
        assertEquals(-1, JapaneseScriptRunGrouping.reduplicationUnit("ドキド", 0, 3));
        assertEquals(2, JapaneseScriptRunGrouping.reduplicationUnit("ドキドキ", 0, 4));
        assertEquals(2, JapaneseScriptRunGrouping.reduplicationUnit("ドキドキドキ", 0, 6));
        // A single elongated character is not reduplication - splitting it would leave a line
        // starting on a character that may not start one.
        assertEquals(-1, JapaneseScriptRunGrouping.reduplicationUnit("ンンンン", 0, 4));
    }

    @Test
    public void scriptClassifiersCoverTheCommonRanges() {
        assertTrue(JapaneseScriptRunGrouping.isKanji('約'));
        assertTrue(JapaneseScriptRunGrouping.isKatakana('ド'));
        assertTrue(JapaneseScriptRunGrouping.isHiragana('あ'));
        assertFalse(JapaneseScriptRunGrouping.isKatakana('ー'));
        assertTrue(JapaneseScriptRunGrouping.isKanaTail('ー'));
        assertTrue(JapaneseScriptRunGrouping.isSmallKana('ッ'));
    }

    private static List<String> splitAll(String text) {
        return texts(text);
    }

    private static List<String> texts(String text) {
        List<String> out = new ArrayList<>();
        for (DisplayLayoutGroup group : JapaneseScriptRunGrouping.forText(text)) {
            out.add(text.substring(group.start, group.end));
        }
        return out;
    }

    private static List<String> list(String... values) {
        List<String> out = new ArrayList<>();
        for (String value : values) out.add(value);
        return out;
    }
}
