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
        assertEquals("Off", Settings.AUTO_SKIP_INTRO_OUTRO.defaultValue);
        assertEquals(java.util.Arrays.asList("Off", "On demand", "Auto"),
                Settings.AUTO_SKIP_INTRO_OUTRO.allowedValues);
        assertEquals("Auto", Settings.AUTO_SKIP_INTRO_OUTRO.coerce("Auto"));
        assertEquals("Off", Settings.AUTO_SKIP_INTRO_OUTRO.coerce("bogus"));
        assertFalse(Settings.MINI_PLAYER_LYRICS_ICON.defaultValue);
        assertFalse(Settings.KARAOKE_ORIGINAL_LYRICS.defaultValue);
        assertEquals("Single tap", Settings.PANEL_MEDIA_CONTROLS.defaultValue);
        assertEquals(java.util.Arrays.asList("Off", "Single tap", "Double tap"),
                Settings.PANEL_MEDIA_CONTROLS.allowedValues);
        assertEquals("Double tap", Settings.PANEL_MEDIA_CONTROLS.coerce("Double tap"));
        assertEquals("Single tap", Settings.PANEL_MEDIA_CONTROLS.coerce("bogus"));

        // Apple Music style: selector gains the Apple option, sub-settings are Apple-owned with
        // PR9's on-values, slide stays off, lift on. STYLE default is unchanged (Gradient wash).
        assertEquals("Gradient wash", Settings.ANIMATION_STYLE.defaultValue);
        assertEquals(java.util.Arrays.asList("Gradient wash", "Spotlight", "Apple Music"),
                Settings.ANIMATION_STYLE.allowedValues);
        assertEquals("Apple Music", Settings.ANIMATION_STYLE.coerce("Apple Music"));
        assertEquals("Gradient wash", Settings.ANIMATION_STYLE.coerce("bogus"));
        assertTrue(Settings.APPLE_FADE_PASSED_LINES.defaultValue);
        assertTrue(Settings.APPLE_COMPACT_TEXT.defaultValue);
        assertFalse(Settings.LINE_SLIDE_ANIMATION.defaultValue);
        assertTrue(Settings.APPLE_LIFT.defaultValue);
        assertEquals(Settings.APPLE, Settings.APPLE_LIFT.section);
        assertTrue(Settings.AUTO_RESUME_FOLLOW.defaultValue);
        assertFalse(Settings.FOLLOW_CHIP_ANIMATION.defaultValue);
        assertEquals(Settings.LYRICS, Settings.FOLLOW_CHIP_ANIMATION.section);
        assertFalse(Settings.FOLLOW_CHIP_PROGRESS.defaultValue);
        assertEquals(Settings.LYRICS, Settings.FOLLOW_CHIP_PROGRESS.section);
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
        assertTrue(Settings.KOREAN_ROMANIZATION.allowedValues.contains("Off"));
        assertEquals("Off", Settings.KOREAN_ROMANIZATION.coerce("Off"));
        assertEquals("Off", Settings.KOREAN_ROMANIZATION.coerce("off"));

        assertEquals(Integer.valueOf(100), Settings.LYRICS_BLUR_INTENSITY.defaultValue);
        assertEquals(25, Settings.LYRICS_BLUR_INTENSITY.minValue);
        assertEquals(250, Settings.LYRICS_BLUR_INTENSITY.maxValue);
        assertEquals(5, Settings.LYRICS_BLUR_INTENSITY.stepValue);

        assertEquals(Integer.valueOf(59), Settings.FURIGANA_BRIGHTNESS.defaultValue);
        assertEquals(20, Settings.FURIGANA_BRIGHTNESS.minValue);
        assertEquals(100, Settings.FURIGANA_BRIGHTNESS.maxValue);
        assertEquals(5, Settings.FURIGANA_BRIGHTNESS.stepValue);

        assertEquals(Integer.valueOf(100), Settings.FURIGANA_POSITION_PERCENT.defaultValue);
        assertEquals(40, Settings.FURIGANA_POSITION_PERCENT.minValue);
        assertEquals(200, Settings.FURIGANA_POSITION_PERCENT.maxValue);
        assertEquals(10, Settings.FURIGANA_POSITION_PERCENT.stepValue);

        // Text glow defaults ON since the B322+ desktop-parity rework made it subtle and cheap.
        assertEquals("Word/syllable synced only", Settings.WORD_BOUNCE.defaultValue);
        assertEquals("Phrase zoom", Settings.WORD_BOUNCE_STYLE.defaultValue);
        assertEquals(java.util.Arrays.asList("Phrase zoom", "Word zoom", "Phrase lift", "Word lift", "Apple lift"),
                Settings.WORD_BOUNCE_STYLE.allowedValues);
        assertEquals("Apple lift", Settings.WORD_BOUNCE_STYLE.coerce("Apple lift"));
        assertEquals("Phrase zoom", Settings.WORD_BOUNCE_STYLE.coerce("bogus"));
        assertFalse(Settings.ALIGNED_PER_WORD_ROMAJI.defaultValue);
        assertTrue(Settings.ENABLE_GLOW_BLUR.defaultValue);
        assertEquals("Off", Settings.ENABLE_LINE_BLUR.defaultValue);
        assertEquals(java.util.Arrays.asList("Off", "Slight", "Heavy"),
                Settings.ENABLE_LINE_BLUR.allowedValues);
        assertEquals("Heavy", Settings.ENABLE_LINE_BLUR.coerce("Heavy"));
        assertEquals("Off", Settings.ENABLE_LINE_BLUR.coerce("bogus"));
        assertTrue(Settings.FORCE_DARK_BACKGROUND.defaultValue);
        assertEquals(Integer.valueOf(35), Settings.EXTRA_DARK_BACKGROUND.defaultValue);
    }
}
