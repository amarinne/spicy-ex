package com.eza.spicyex.lyrics;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class JyutpingRomanizerTest {
    @Test
    public void phraseTrieOverridesCharacterFallback() {
        assertEquals("soeng5 tong4 zung1 jyu1 gong2 dou3 fan1 sou3",
                JyutpingRomanizer.romanize("上堂終於講到分數"));
        assertEquals("hoi1 wui2 zing6 dai1 ge3 je5 ngo5 wui5 gaau2 dim6 gaa3 laa3",
                JyutpingRomanizer.romanize("開會剩低嘅嘢我會搞掂㗎喇"));
    }

    @Test
    public void fallsBackToCharacterMapForUnmatchedText() {
        assertEquals("hoeng1 gong2", JyutpingRomanizer.romanize("香港"));
        assertEquals("hoeng1 gong2 A", JyutpingRomanizer.romanize("香港A"));
    }

    @Test
    public void lineWrapperCanStripToneNumbers() {
        assertEquals("hoeng1 gong2", SpicyJapaneseChineseProcessor.romanizeChineseLine("香港", "jyutping", true));
        assertEquals("hoeng gong", SpicyJapaneseChineseProcessor.romanizeChineseLine("香港", "jyutping", false));
    }
}
