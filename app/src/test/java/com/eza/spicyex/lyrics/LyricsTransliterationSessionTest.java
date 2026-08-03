package com.eza.spicyex.lyrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.eza.spicyex.SpotifyPlusConfig;

import java.lang.reflect.Constructor;

import org.junit.Test;

public class LyricsTransliterationSessionTest {
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

    private static LyricsRenderConfig configWithKorean(String koreanModeConfig, String defaultKoreanMode,
                                                       String koreanMode) throws Exception {
        Constructor<LyricsRenderConfig> ctor = LyricsRenderConfig.class.getDeclaredConstructor(
                boolean.class, boolean.class, boolean.class, boolean.class, boolean.class, boolean.class, float.class,
                boolean.class, boolean.class, boolean.class, boolean.class, boolean.class,
                String.class, float.class, String.class, String.class, String.class, String.class, float.class,
                String.class, float.class, String.class, boolean.class, boolean.class, boolean.class,
                String.class, String.class, String.class, String.class, String.class, String.class, String.class,
                String.class, String.class, String.class, String.class, String.class, String.class, String.class,
                boolean.class, String.class, String.class, String.class, boolean.class,
                boolean.class, String.class, String.class, boolean.class, int.class);
        ctor.setAccessible(true);
        return ctor.newInstance(
                false, true, true, false, true, true, 1f,
                false, true, true, true, true,
                "more", 1f, "Medium", "Medium", "default", "normal", 1f,
                "normal", 1f, "Main only", false, false, false,
                "Spotlight word", "Off", "Top to bottom", "Fade up", "Scroll with lyric", "Grouped", "Top to bottom",
                "cycle", SpotifyPlusConfig.JP_READING_FURIGANA_ROMAJI,
                "cycle", SpotifyPlusConfig.CHINESE_MODE_PINYIN,
                koreanModeConfig, defaultKoreanMode, koreanMode,
                false,
                "cycle", SpicyRomanizer.CYRILLIC_RUSSIAN, SpicyRomanizer.CYRILLIC_RUSSIAN, false,
                true, "google_unofficial", "en", false, 0);
    }
}
