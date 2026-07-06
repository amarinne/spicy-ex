package com.eza.spicyex.lyrics;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class LyricUtilsTest {
    @Test
    public void cleanInvisiblesRemovesZeroWidthAndBom() {
        assertEquals("Thisis", LyricUtils.cleanInvisibles("This\u200Bis"));
        assertEquals("This is a", LyricUtils.cleanInvisibles("This \u200Bis \u200Ba"));
        assertEquals("clean", LyricUtils.cleanInvisibles("\uFEFFclean\uFEFF"));
    }

    @Test
    public void cleanInvisiblesNormalizesNbspAndRuns() {
        assertEquals("hello world", LyricUtils.cleanInvisibles(" hello\u00A0\t world  "));
        assertEquals("a b c", LyricUtils.cleanInvisibles("a  b\t\tc"));
    }

    @Test
    public void cleanInvisiblesPreservesIndicJoiners() {
        assertEquals("क\u200Dष क\u200Cष", LyricUtils.cleanInvisibles("क\u200Dष\u00A0क\u200Cष"));
    }
}
