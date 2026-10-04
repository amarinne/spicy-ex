package com.eza.spicyex.lyrics.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.eza.spicyex.lyrics.providers.LyricQualityRanker;

import org.junit.Test;

/** Default source gates, legacy mode migration, and within-provider timing helpers. */
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
    public void newInstallUsesAppleSpotifyAndLrclibWithOtherSourcesOptIn() {
        assertFalse(LyricsSourcePreferences.enabledByDefault(null));
        assertFalse(LyricsSourcePreferences.enabledByDefault(
                LyricsSourcePreferences.Source.SPICY));
        assertFalse(LyricsSourcePreferences.enabledByDefault(
                LyricsSourcePreferences.Source.QQ));
        assertFalse(LyricsSourcePreferences.enabledByDefault(
                LyricsSourcePreferences.Source.NETEASE));
        assertTrue(LyricsSourcePreferences.enabledByDefault(
                LyricsSourcePreferences.Source.APPLE_MUSIC));
        assertTrue(LyricsSourcePreferences.enabledByDefault(
                LyricsSourcePreferences.Source.SPOTIFY));
        assertFalse(LyricsSourcePreferences.enabledByDefault(
                LyricsSourcePreferences.Source.AMLL));
        assertTrue(LyricsSourcePreferences.enabledByDefault(
                LyricsSourcePreferences.Source.LRCLIB));
    }

    @Test
    public void timingHelpersOrderVariantsWithinTheSameSource() {
        assertEquals(3, LyricQualityRanker.syncLevel("Syllable"));
        assertEquals(2, LyricQualityRanker.syncLevel("Word"));
        assertEquals(1, LyricQualityRanker.syncLevel("Line"));
        assertEquals(0, LyricQualityRanker.syncLevel("Static"));
    }
}
