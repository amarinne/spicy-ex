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
        assertEquals("Fullscreen", Settings.LIVE_CARD_TAP_TARGET.defaultValue);
        assertEquals("lyrics_live_card_tap_target", Settings.LIVE_CARD_TAP_TARGET.key);
        assertEquals(java.util.Arrays.asList("Fullscreen", "Artwork"),
                Settings.LIVE_CARD_TAP_TARGET.allowedValues);
        assertEquals("spacious", Settings.LINE_SPACING.defaultValue);
        assertEquals("note", Settings.INTERLUDE_ICON.defaultValue);
        assertTrue(Settings.AUTO_RESUME_FOLLOW.defaultValue);
        assertFalse(Settings.HYPERGLOW_ENABLED.defaultValue);
        assertEquals("en", Settings.UI_LANGUAGE.defaultValue);
        // Default stays Google draft until device comparison proves another flow better; adding
        // the preview experiment must not migrate either existing stored choice.
        assertEquals("Google draft", Settings.AI_TRANSLATION_PIPELINE.defaultValue);
        assertEquals(java.util.Arrays.asList("Google preview", "Google draft", "AI only"),
                Settings.AI_TRANSLATION_PIPELINE.allowedValues);
        assertEquals("Google preview", Settings.AI_TRANSLATION_PIPELINE.coerce("Google preview"));
        assertEquals("Google draft", Settings.AI_TRANSLATION_PIPELINE.coerce("Google draft"));
        assertEquals("AI only", Settings.AI_TRANSLATION_PIPELINE.coerce("AI only"));
        assertEquals("Layered", Settings.AI_PRONUNCIATION_SOURCE.allowedValues.get(0));
        assertEquals("AI only", Settings.AI_PRONUNCIATION_SOURCE.allowedValues.get(1));

        assertFalse(Settings.TRANSLITERATION_ENABLED.defaultValue);
        assertFalse(Settings.TRANSLATION_ENABLED.defaultValue);
        assertEquals("google_unofficial", Settings.TRANSLATION_BACKEND.defaultValue);
        assertEquals(Settings.INTERNAL, Settings.TRANSLATION_BACKEND.section);
        assertEquals(2, Settings.TRANSLATION_BACKEND.allowedValues.size());
        assertEquals("provider", Settings.TRANSLATION_BACKEND.coerce("provider"));
        assertFalse(Settings.NATIVE_SPICY_ROMANIZATION.defaultValue);
        assertFalse(Settings.NATIVE_SPICY_TRANSLATION.defaultValue);

        assertEquals(SpotifyPlusConfig.JP_READING_ROMAJI_ONLY, Settings.JAPANESE_READING_MODE.defaultValue);
        assertEquals(SpotifyPlusConfig.CHINESE_MODE_PINYIN, Settings.CHINESE_MODE.defaultValue);
        assertEquals(KoreanDisplayMode.RR_STANDARD.value, Settings.KOREAN_ROMANIZATION.defaultValue);

        // Text glow defaults ON since the B322+ desktop-parity rework made it subtle and cheap.
        assertTrue(Settings.WORD_BOUNCE.defaultValue);
        assertTrue(Settings.ENABLE_GLOW_BLUR.defaultValue);
        assertFalse(Settings.ENABLE_LINE_BLUR.defaultValue);
        assertTrue(Settings.FORCE_DARK_BACKGROUND.defaultValue);
    }
}
