package com.eza.spicyex.lyrics.session;

import static org.junit.Assert.assertEquals;

import com.eza.spicyex.lyrics.LyricsCandidateSelector;
import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.lyrics.LyricsLine;

import org.junit.Test;

/** Auto ranking accepts syllable > word > line > static, and legacy modes migrate to it. */
public class LyricsSourceRankingTest {
    @Test
    public void legacyRankingModesParseToAuto() {
        assertEquals(LyricsSourcePreferences.RankingMode.AUTO,
                LyricsSourcePreferences.RankingMode.parse("Smart ranking"));
        assertEquals(LyricsSourcePreferences.RankingMode.AUTO,
                LyricsSourcePreferences.RankingMode.parse("Sync type"));
        assertEquals(LyricsSourcePreferences.RankingMode.AUTO,
                LyricsSourcePreferences.RankingMode.parse("smart"));
        assertEquals(LyricsSourcePreferences.RankingMode.AUTO,
                LyricsSourcePreferences.RankingMode.parse("sync"));
        assertEquals(LyricsSourcePreferences.RankingMode.AUTO,
                LyricsSourcePreferences.RankingMode.parse(null));
    }

    @Test
    public void sourceOrderModeSurvivesMigration() {
        assertEquals(LyricsSourcePreferences.RankingMode.SOURCE_ORDER,
                LyricsSourcePreferences.RankingMode.parse("Source order"));
        assertEquals(LyricsSourcePreferences.RankingMode.SOURCE_ORDER,
                LyricsSourcePreferences.RankingMode.parse("order"));
    }

    @Test
    public void candidateSyncLevelMatchesAutoOrder() {
        assertEquals(3, LyricsCandidateSelector.syncLevel(doc("Syllable")));
        assertEquals(2, LyricsCandidateSelector.syncLevel(doc("Word")));
        assertEquals(1, LyricsCandidateSelector.syncLevel(doc("Line")));
        assertEquals(0, LyricsCandidateSelector.syncLevel(doc("Static")));
    }

    private static LyricsDocument doc(String type) {
        LyricsDocument doc = new LyricsDocument();
        doc.type = type;
        LyricsLine line = new LyricsLine();
        line.text = "hello";
        doc.lines.add(line);
        return doc;
    }
}
