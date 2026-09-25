package com.eza.spicyex.lyrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

public class CjkLineBreakTest {

    private static List<String> split(String text) {
        List<String> parts = new ArrayList<>();
        for (int[] range : CjkLineBreak.clusters(text)) parts.add(text.substring(range[0], range[1]));
        return parts;
    }

    @Test
    public void breaksBetweenPlainCharacters() {
        assertEquals(java.util.Arrays.asList("君", "の", "名", "前"), split("君の名前"));
    }

    @Test
    public void closingMarksAndSmallKanaNeverStartALine() {
        assertEquals(java.util.Arrays.asList("きっ", "と"), split("きっと"));
        assertEquals(java.util.Arrays.asList("ラー", "メ", "ン"), split("ラーメン"));
        assertEquals(java.util.Arrays.asList("好", "き。"), split("好き。"));
    }

    @Test
    public void openingBracketsNeverEndALine() {
        List<String> parts = split("「夢」を");
        assertEquals("「夢」", parts.get(0));
    }

    @Test
    public void embeddedLatinWordsStayWhole() {
        assertEquals(java.util.Arrays.asList("I ", "love", "君"), split("I love君"));
        assertEquals(java.util.Arrays.asList("LOVE123", "だ", "よ"), split("LOVE123だよ"));
    }

    @Test
    public void coversTheWholeStringInOrder() {
        String text = "今日も、明日もLOVE123だよ！";
        StringBuilder joined = new StringBuilder();
        for (String part : split(text)) joined.append(part);
        assertEquals(text, joined.toString());
    }

    @Test
    public void onlyHanAndKanaFlowByCharacter() {
        assertTrue(CjkLineBreak.flowsByCharacter("君の名前"));
        assertFalse("Korean breaks at words", CjkLineBreak.flowsByCharacter("사랑해"));
        assertFalse(CjkLineBreak.flowsByCharacter("love"));
        assertFalse("a single character has nothing to break", CjkLineBreak.flowsByCharacter("君"));
    }
}
