package com.eza.spicyex.lyrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class DisplayLayoutGroupTest {
    @Test
    public void japaneseParticleAttachesToPreviousLexeme() {
        String text = "健やかな産声を聞けたなら";
        SpicyJapaneseChineseProcessor.JapaneseReading reading =
                SpicyJapaneseChineseProcessor.analyzeJapaneseLine(text, null);

        List<DisplayLayoutGroup> groups = DisplayLayoutGroup.forLine("ja", text, reading);

        assertTrue(groups.stream().anyMatch(group ->
                "産声を".equals(text.substring(group.start, group.end))));
    }

    @Test
    public void chineseKnownPhraseStaysTogether() {
        String text = "音乐响起";

        List<DisplayLayoutGroup> groups = DisplayLayoutGroup.forLine("zh", text, null);

        assertEquals("音乐", text.substring(groups.get(0).start, groups.get(0).end));
    }

    @Test
    public void koreanAuthoredSpacesRemainBreakBoundaries() {
        List<DisplayLayoutGroup> groups = DisplayLayoutGroup.forLine("ko", "나는 너를", null);

        assertEquals(2, groups.size());
        assertEquals(0, groups.get(0).start);
        assertEquals(2, groups.get(0).end);
        assertEquals(3, groups.get(1).start);
    }
}
