package com.eza.spicyex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.eza.spicyex.lyrics.KoreanDisplayMode;

import org.junit.Test;

public class SettingsDefaultsTest {
    @Test
    public void quietReadableDefaultsAreOptInForProcessing() {
        assertEquals("Karaoke fill", Settings.LIVE_CARD_ANIMATION.defaultValue);
        assertEquals("spacious", Settings.LINE_SPACING.defaultValue);
        assertEquals("note", Settings.INTERLUDE_ICON.defaultValue);
        assertTrue(Settings.AUTO_RESUME_FOLLOW.defaultValue);
        assertFalse(Settings.HYPERGLOW_ENABLED.defaultValue);

        assertFalse(Settings.TRANSLITERATION_ENABLED.defaultValue);
        assertFalse(Settings.TRANSLATION_ENABLED.defaultValue);
        assertFalse(Settings.NATIVE_SPICY_ROMANIZATION.defaultValue);
        assertFalse(Settings.NATIVE_SPICY_TRANSLATION.defaultValue);

        assertEquals(SpotifyPlusConfig.JP_READING_ROMAJI_ONLY, Settings.JAPANESE_READING_MODE.defaultValue);
        assertEquals(SpotifyPlusConfig.CHINESE_MODE_PINYIN, Settings.CHINESE_MODE.defaultValue);
        assertEquals(KoreanDisplayMode.RR_STANDARD.value, Settings.KOREAN_ROMANIZATION.defaultValue);

        // Text glow defaults ON since the B322+ desktop-parity rework made it subtle and cheap.
        assertTrue(Settings.ENABLE_GLOW_BLUR.defaultValue);
        assertFalse(Settings.ENABLE_LINE_BLUR.defaultValue);
        assertTrue(Settings.FORCE_DARK_BACKGROUND.defaultValue);
    }
}
