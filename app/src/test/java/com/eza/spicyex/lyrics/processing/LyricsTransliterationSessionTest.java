package com.eza.spicyex.lyrics.processing;

import com.eza.spicyex.lyrics.language.KoreanDisplayMode;
import com.eza.spicyex.lyrics.language.SpicyRomanizer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.eza.spicyex.SpotifyPlusConfig;

import org.junit.Test;
import com.eza.spicyex.lyrics.LyricsRenderConfig;

public class LyricsTransliterationSessionTest {
    @Test
    public void explicitAiVisibilityDoesNotAdvanceTheCurrentMode() throws Exception {
        LyricsTransliterationSession session = new LyricsTransliterationSession(
                false,
                cycleConfig(),
                SpotifyPlusConfig.JP_READING_ROMAJI_ONLY,
                SpotifyPlusConfig.CHINESE_MODE_JYUTPING,
                KoreanDisplayMode.VN_PRONUNCIATION.value,
                SpicyRomanizer.CYRILLIC_UKRAINIAN);

        session.setShowRomanization(true);

        assertTrue(session.showRomanization());
        assertEquals(SpotifyPlusConfig.JP_READING_ROMAJI_ONLY, session.japaneseReadingMode());
        assertEquals(SpotifyPlusConfig.CHINESE_MODE_JYUTPING, session.chineseMode());
        assertEquals(KoreanDisplayMode.VN_PRONUNCIATION.value, session.koreanMode());
        assertEquals(SpicyRomanizer.CYRILLIC_UKRAINIAN, session.cyrillicMode());
    }

    @Test
    public void dataBackedChipClickKeepsLatentVisibilityOnWhileOutputIsRequested() throws Exception {
        LyricsTransliterationSession session = new LyricsTransliterationSession(true, cycleConfig());

        assertTrue(session.keepVisibleForRequestedOutput(true, true, false));
        assertTrue(session.showRomanization());
    }

    @Test
    public void generationFromAnActuallyOffChipLeavesVisibilityOn() throws Exception {
        LyricsTransliterationSession session = new LyricsTransliterationSession(false, cycleConfig());

        assertTrue(session.keepVisibleForRequestedOutput(true, false, true));
    }

    @Test
    public void ordinaryChipClicksStillCycleWhenVisibilityOrOutputStateIsUnambiguous() throws Exception {
        LyricsTransliterationSession off = new LyricsTransliterationSession(false, cycleConfig());
        LyricsTransliterationSession visibleWithOutput =
                new LyricsTransliterationSession(true, cycleConfig());

        assertTrue(off.keepVisibleForRequestedOutput(true, false, false));
        assertFalse(visibleWithOutput.keepVisibleForRequestedOutput(true, true, true));
        assertFalse(visibleWithOutput.keepVisibleForRequestedOutput(false, true, false));
    }

    @Test
    public void koreanCycleRestoresLastModeWhenOpenedOff() throws Exception {
        LyricsTransliterationSession session = new LyricsTransliterationSession(
                false,
                cycleConfig(),
                null,
                null,
                KoreanDisplayMode.VN_PRONUNCIATION.value,
                null);

        LyricsTransliterationSession.CycleResult result = session.cycle(false, false, true, false);

        assertTrue(result.showRomanization);
        assertEquals(KoreanDisplayMode.RR_STANDARD.value, session.koreanMode());
    }

    @Test
    public void koreanCycleWalksFourModesThenTurnsOff() throws Exception {
        LyricsTransliterationSession session = new LyricsTransliterationSession(
                false,
                cycleConfig(),
                null,
                null,
                null,
                null);

        LyricsTransliterationSession.CycleResult result = session.cycle(false, false, true, false);
        assertTrue(result.showRomanization);
        assertEquals(KoreanDisplayMode.RR_STANDARD.value, session.koreanMode());

        result = session.cycle(false, false, true, false);
        assertTrue(result.showRomanization);
        assertEquals(KoreanDisplayMode.WORD_TRANSLIT.value, session.koreanMode());

        result = session.cycle(false, false, true, false);
        assertTrue(result.showRomanization);
        assertEquals(KoreanDisplayMode.RR_PRONUNCIATION.value, session.koreanMode());

        result = session.cycle(false, false, true, false);
        assertTrue(result.showRomanization);
        assertEquals(KoreanDisplayMode.VN_PRONUNCIATION.value, session.koreanMode());

        result = session.cycle(false, false, true, false);

        assertFalse(result.showRomanization);
        assertEquals(KoreanDisplayMode.VN_PRONUNCIATION.value, session.koreanMode());
    }

    @Test
    public void japaneseCycleRestoresLastModeWhenOpenedOff() {
        LyricsTransliterationSession session = new LyricsTransliterationSession(
                false,
                LyricsRenderConfig.read(null, null),
                SpotifyPlusConfig.JP_READING_ROMAJI_ONLY,
                null,
                null,
                null);

        LyricsTransliterationSession.CycleResult result = session.cycle(true, false, false, false);

        assertTrue(result.showRomanization);
        assertEquals(SpotifyPlusConfig.JP_READING_ROMAJI_ONLY, session.japaneseReadingMode());
    }

    @Test
    public void chineseCycleRestartsAtPinyinAfterOff() throws Exception {
        LyricsTransliterationSession session = new LyricsTransliterationSession(
                false,
                cycleConfig(),
                null,
                SpotifyPlusConfig.CHINESE_MODE_JYUTPING,
                null,
                null);

        LyricsTransliterationSession.CycleResult result = session.cycle(false, true, false, false);

        assertTrue(result.showRomanization);
        assertEquals(SpotifyPlusConfig.CHINESE_MODE_PINYIN, session.chineseMode());
    }

    @Test
    public void renderConfigCarriesKoreanCycleCurrentModeSeparately() throws Exception {
        LyricsRenderConfig config = configWithKorean("cycle", KoreanDisplayMode.RR_STANDARD.value, KoreanDisplayMode.VN_PRONUNCIATION.value);

        assertEquals("cycle", config.koreanModeConfig);
        assertEquals(KoreanDisplayMode.RR_STANDARD.value, config.defaultKoreanMode);
        assertEquals(KoreanDisplayMode.VN_PRONUNCIATION.value, config.koreanMode);
    }

    @Test
    public void renderConfigCarriesFixedKoreanModeAsCurrentMode() throws Exception {
        LyricsRenderConfig config = configWithKorean(KoreanDisplayMode.WORD_TRANSLIT.value,
                KoreanDisplayMode.WORD_TRANSLIT.value, KoreanDisplayMode.WORD_TRANSLIT.value);

        assertEquals(KoreanDisplayMode.WORD_TRANSLIT.value, config.koreanModeConfig);
        assertEquals(KoreanDisplayMode.WORD_TRANSLIT.value, config.defaultKoreanMode);
        assertEquals(KoreanDisplayMode.WORD_TRANSLIT.value, config.koreanMode);
    }

    private static LyricsRenderConfig cycleConfig() throws Exception {
        return configWithKorean("cycle", KoreanDisplayMode.WORD_TRANSLIT.value, KoreanDisplayMode.WORD_TRANSLIT.value);
    }

    /**
     * A render config with every reading mode on the cycle, so the session walks the modes the
     * way it does on a device.
     */
    private static LyricsRenderConfig configWithKorean(String koreanModeConfig, String defaultKoreanMode,
                                                       String koreanMode) throws Exception {
        LyricsRenderConfig config = LyricsRenderConfig.read(null, null);
        set(config, "transliterationEnabled", true);
        set(config, "japaneseModeConfig", "cycle");
        set(config, "defaultJapaneseReadingMode", SpotifyPlusConfig.JP_READING_FURIGANA_ROMAJI);
        set(config, "chineseModeConfig", "cycle");
        set(config, "defaultChineseMode", SpotifyPlusConfig.CHINESE_MODE_PINYIN);
        set(config, "cyrillicModeConfig", "cycle");
        set(config, "defaultCyrillicMode", SpicyRomanizer.CYRILLIC_RUSSIAN);
        set(config, "cyrillicMode", SpicyRomanizer.CYRILLIC_RUSSIAN);
        set(config, "koreanModeConfig", koreanModeConfig);
        set(config, "defaultKoreanMode", defaultKoreanMode);
        set(config, "koreanMode", koreanMode);
        return config;
    }

    private static void set(LyricsRenderConfig config, String field, Object value) throws Exception {
        java.lang.reflect.Field target = LyricsRenderConfig.class.getDeclaredField(field);
        target.setAccessible(true);
        target.set(config, value);
    }

    @Test
    public void japaneseCycleWalksThreeModesThenTurnsOff() throws Exception {
        // Seeded with the mode the user was last left on — the common case, and the one that used
        // to collapse the whole cycle into an on/off toggle.
        LyricsTransliterationSession session = new LyricsTransliterationSession(
                false, cycleConfig(), SpotifyPlusConfig.JP_READING_FURIGANA_ROMAJI, null, null, null);

        LyricsTransliterationSession.CycleResult result = session.cycle(true, false, false, false);
        assertTrue(result.showRomanization);
        assertEquals(SpotifyPlusConfig.JP_READING_FURIGANA_ONLY, session.japaneseReadingMode());

        result = session.cycle(true, false, false, false);
        assertTrue(result.showRomanization);
        assertEquals(SpotifyPlusConfig.JP_READING_ROMAJI_ONLY, session.japaneseReadingMode());

        result = session.cycle(true, false, false, false);
        assertTrue(result.showRomanization);
        assertEquals(SpotifyPlusConfig.JP_READING_FURIGANA_ROMAJI, session.japaneseReadingMode());

        result = session.cycle(true, false, false, false);
        assertFalse("the cycle closes by turning readings off", result.showRomanization);

        result = session.cycle(true, false, false, false);
        assertTrue("and re-enters at the first mode, not the last", result.showRomanization);
        assertEquals(SpotifyPlusConfig.JP_READING_FURIGANA_ONLY, session.japaneseReadingMode());
    }

    @Test
    public void cyrillicCycleWalksBothModesThenTurnsOff() throws Exception {
        LyricsTransliterationSession session = new LyricsTransliterationSession(
                false, cycleConfig(), null, null, null, SpicyRomanizer.CYRILLIC_UKRAINIAN);

        LyricsTransliterationSession.CycleResult result = session.cycle(false, false, false, true);
        assertTrue(result.showRomanization);
        assertEquals(SpicyRomanizer.CYRILLIC_RUSSIAN, session.cyrillicMode());

        result = session.cycle(false, false, false, true);
        assertTrue(result.showRomanization);
        assertEquals(SpicyRomanizer.CYRILLIC_UKRAINIAN, session.cyrillicMode());

        result = session.cycle(false, false, false, true);
        assertFalse(result.showRomanization);
    }
}
