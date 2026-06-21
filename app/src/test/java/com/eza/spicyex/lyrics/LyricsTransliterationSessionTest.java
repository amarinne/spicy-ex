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
                SpicyRomanizer.KOREAN_PRONUNCIATION,
                null);

        LyricsTransliterationSession.CycleResult result = session.cycle(false, false, true, false);

        assertTrue(result.showRomanization);
        assertEquals(SpicyRomanizer.KOREAN_PRONUNCIATION, session.koreanMode());
    }

    @Test
    public void koreanCycleTurnsOffAfterRestoredLastMode() throws Exception {
        LyricsTransliterationSession session = new LyricsTransliterationSession(
                true,
                cycleConfig(),
                null,
                null,
                SpicyRomanizer.KOREAN_PRONUNCIATION,
                null);

        LyricsTransliterationSession.CycleResult result = session.cycle(false, false, true, false);

        assertFalse(result.showRomanization);
        assertEquals(SpicyRomanizer.KOREAN_PRONUNCIATION, session.koreanMode());
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

    private static LyricsRenderConfig cycleConfig() throws Exception {
        Constructor<LyricsRenderConfig> ctor = LyricsRenderConfig.class.getDeclaredConstructor(
                boolean.class, boolean.class, boolean.class, boolean.class, boolean.class, boolean.class, float.class,
                boolean.class, boolean.class, boolean.class, boolean.class,
                String.class, float.class, String.class, String.class, String.class, String.class, float.class,
                String.class, float.class, boolean.class, boolean.class, String.class,
                String.class, String.class, String.class, String.class, String.class, String.class, String.class,
                boolean.class, String.class, String.class, String.class, boolean.class,
                boolean.class, String.class, boolean.class, int.class);
        ctor.setAccessible(true);
        return ctor.newInstance(
                false, true, true, false, true, true, 1f,
                false, true, true, true,
                "more", 1f, "Medium", "Medium", "default", "normal", 1f,
                "normal", 1f, false, false, "Top to bottom",
                "cycle", SpotifyPlusConfig.JP_READING_FURIGANA_ROMAJI,
                "cycle", SpotifyPlusConfig.CHINESE_MODE_PINYIN,
                "cycle", "Letter-by-letter", "Letter-by-letter",
                false,
                "cycle", SpicyRomanizer.CYRILLIC_RUSSIAN, SpicyRomanizer.CYRILLIC_RUSSIAN, false,
                true, "en", false, 0);
    }
}
