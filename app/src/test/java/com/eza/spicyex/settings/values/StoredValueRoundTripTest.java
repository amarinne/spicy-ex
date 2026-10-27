package com.eza.spicyex.settings.values;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.eza.spicyex.Settings;
import com.eza.spicyex.settings.registry.SettingsSnapshot;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * S6: every stored-label enum keeps the labels the preference already holds.
 *
 * <p>The expected labels below are the values the settings carried before the enums existed, so a
 * change to any of them fails here: the stored value, the default and the round trip
 * (label to value to label) are the contract the existing installs depend on.
 */
public class StoredValueRoundTripTest {

    @Test
    public void secondaryTextModeKeepsItsLabels() {
        assertRoundTrip(Settings.AUTO_SECONDARY_TEXT, SecondaryTextMode.class, "Main only",
                "Main only", "Transliteration", "Translation", "Both");
        assertRoundTrip(Settings.LIVE_CARD_SECONDARY_MODE, SecondaryTextMode.class, "Main only",
                "Main only", "Transliteration", "Translation", "Both");
    }

    @Test
    public void chipModesKeepTheirLabels() {
        assertRoundTrip(Settings.SKIP_CHIP_STYLE, ChipStyle.class, "Auto",
                "Icon", "Label", "Auto");
        assertRoundTrip(Settings.FOLLOW_CHIP_STYLE, ChipStyle.class, "Auto",
                "Icon", "Label", "Auto");
        assertRoundTrip(Settings.SKIP_CHIP_POSITION, ChipPosition.class, "Right",
                "Left", "Center", "Right");
        assertRoundTrip(Settings.FOLLOW_CHIP_POSITION, ChipPosition.class, "Right",
                "Left", "Center", "Right");
        assertRoundTrip(Settings.FOLLOW_CHIP_ICON, FollowChipIcon.class, "Adaptive arrow",
                "Adaptive arrow", "Static arrow", "Waveform");
    }

    @Test
    public void textModesKeepTheirLabels() {
        assertRoundTrip(Settings.LYRICS_WEIGHT, TextWeight.class, "Medium",
                "Regular", "Medium", "Bold");
        assertRoundTrip(Settings.LIVE_CARD_WEIGHT, TextWeight.class, "Medium",
                "Regular", "Medium", "Bold");
        assertRoundTrip(Settings.LYRICS_FONT, LyricsFont.class, "spotify",
                "spotify", "apple", "custom");
        assertRoundTrip(Settings.LYRICS_TEXT_SIZE, TextSizeMode.class, "normal",
                "small", "normal", "large", "xlarge", "custom");
        assertRoundTrip(Settings.LIVE_CARD_TEXT_SIZE, TextSizeMode.class, "normal",
                "small", "normal", "large", "xlarge", "custom");
        assertRoundTrip(Settings.LINE_SPACING, LineSpacingMode.class, "spacious",
                "compact", "default", "spacious", "more", "max", "custom");
        assertRoundTrip(Settings.INTERLUDE_ICON, InterludeIcon.class, "note",
                "dots", "note");
    }

    @Test
    public void bounceModesKeepTheirLabels() {
        assertRoundTrip(Settings.WORD_BOUNCE, WordBounceMode.class, "Word/syllable synced only",
                "Off", "Word/syllable synced only", "All synced rows");
        assertRoundTrip(Settings.WORD_BOUNCE_STYLE, WordBounceStyle.class, "Word zoom",
                "Phrase zoom", "Word zoom", "Phrase lift", "Word lift", "Apple lift");
    }

    @Test
    public void motionAndFocusModesKeepTheirLabels() {
        assertRoundTrip(Settings.ANIMATION_STYLE, AnimationStyle.class, "Gradient wash",
                "Gradient wash", "Spotlight", "Apple Music");
        assertRoundTrip(Settings.LYRICS_FOCUS_POSITION, LyricsFocusPosition.class, "Auto",
                "Auto", "Top", "Center", "Bottom", "Custom");
    }

    @Test
    public void fillModesKeepTheirLabels() {
        assertEquals(Arrays.asList("Top to bottom", "Left to right (block)", "Left to right (sentence)"),
                Settings.LINE_SYNC_FILL.allowedValues);
        assertEquals("Left to right (sentence)", Settings.LINE_SYNC_FILL.defaultValue);
        assertRoundTrip(Settings.LIVE_CARD_LINE_SYNC_FILL, LineSyncFill.class, "Top to bottom",
                "Top to bottom", "Left to right (block)", "Left to right (sentence)");
    }

    @Test
    public void liveCardModesKeepTheirLabels() {
        assertRoundTrip(Settings.LIVE_CARD_ANIMATION, LiveCardAnimation.class, "Karaoke fill",
                "Minimal", "Karaoke fill", "Spotlight word");
        assertRoundTrip(Settings.LIVE_CARD_GLOW, LiveCardGlow.class, "Off",
                "Off", "Word only");
        assertRoundTrip(Settings.LIVE_CARD_TRANSITION, LiveCardTransition.class, "Fade up",
                "Fade up", "Crossfade", "None");
        assertRoundTrip(Settings.LIVE_CARD_OVERFLOW, LiveCardOverflow.class, "Wrap",
                "Wrap", "Scroll with lyric", "Clip");
        assertRoundTrip(Settings.LIVE_CARD_SCROLL_SCOPE, LiveCardScrollScope.class, "Grouped",
                "Grouped", "Individual lines");
    }

    @Test
    public void readoutModesKeepTheirLabels() {
        assertRoundTrip(Settings.TRACK_INFO_POSITION, TrackInfoPosition.class, "Off",
                "Off", "Top", "Bottom", "Header");
        assertRoundTrip(Settings.TRACK_INFO_BACKGROUND, TrackInfoBackground.class, "Gradient",
                "Gradient", "Solid", "None", "Blur");
        assertRoundTrip(Settings.TRACK_INFO_TEXT_ALIGN, TrackInfoTextAlign.class, "Center",
                "Top", "Center", "Bottom");
        assertRoundTrip(Settings.TRACK_INFO_TEXT_OVERFLOW, TrackInfoTextOverflow.class, "Wrap",
                "Clip", "Wrap", "Scroll");
        assertRoundTrip(Settings.TRACK_INFO_TEXT_SIZE, TrackInfoTextSize.class, "Normal",
                "Small", "Normal", "Large", "XLarge", "Custom");
        assertRoundTrip(Settings.TRACK_INFO_ART_SIZE, TrackInfoArtSize.class, "Normal",
                "Small", "Normal", "Large", "Custom");
    }

    @Test
    public void chromeModesKeepTheirLabels() {
        assertRoundTrip(Settings.LIKED_SONGS_BUTTON, LikedSongsButton.class, "Off",
                "Off", "Heart", "Star");
        assertRoundTrip(Settings.CHROME_CLUSTER_POSITION, ChromeClusterPosition.class, "Right",
                "Left", "Right");
        assertRoundTrip(Settings.CHROME_CLUSTER_LAYOUT, ChromeClusterLayout.class, "Auto",
                "Auto", "Vertical", "Horizontal");
        assertRoundTrip(Settings.PANEL_MEDIA_CONTROLS, PanelMediaMode.class, "Single tap",
                "Off", "Single tap", "Double tap");
        assertRoundTrip(Settings.BACKGROUND_STYLE, BackgroundStyle.class, "Gradient",
                "Gradient", "Static texture", "Animated texture");
    }

    @Test
    public void gestureAndStatusBarModesKeepTheirLabels() {
        assertRoundTrip(Settings.TAP_SEEK_MODE, TapSeekMode.class, "Double tap",
                "Off", "Single tap", "Double tap");
        assertRoundTrip(Settings.STATUS_BAR_HIDDEN_MODE, StatusBarHiddenMode.class, "Off",
                "Off", "Portrait", "Landscape", "Both");
    }

    @Test
    public void pipModesKeepTheirLabels() {
        assertRoundTrip(Settings.PIP_SHAPE, PipShape.class, "Portrait",
                "Portrait", "Square", "Tall", "Landscape", "Wide");
        assertRoundTrip(Settings.PIP_FOCUS, PipFocus.class, "Same as lyrics screen",
                "Same as lyrics screen", "Center");
    }

    @Test
    public void playbackAndMotionModesKeepTheirLabels() {
        assertRoundTrip(Settings.AUTO_SKIP_INTRO_OUTRO, AutoSkipIntroOutro.class, "Off",
                "Off", "On demand", "Auto");
        assertRoundTrip(Settings.TRANSITION_FEEL, TransitionFeel.class, "Fast",
                "Instant", "Fast", "Relaxed");
        assertRoundTrip(Settings.BACKGROUND_QUALITY, BackgroundQuality.class, "high",
                "high", "mid", "low", "superLow");
        assertRoundTrip(Settings.DISPLAY_MODE, DisplayMode.class, "original_romanized",
                "original", "romanized", "original_romanized",
                "original_translation", "original_romanized_translation");
    }

    @Test
    public void sourceModesKeepTheirLabels() {
        assertRoundTrip(Settings.LYRICS_SOURCE_MODE, LyricsSourceMode.class, "Auto",
                "Auto", "Source order");
        assertRoundTrip(Settings.LYRICS_SOURCE_OVERRIDE, LyricsSourceOverride.class, "Auto",
                "Auto", "Apple Music", "Spicy", "Spotify", "LRCLIB");
    }

    @Test
    public void liveCardTapModesKeepTheirLabels() {
        assertRoundTrip(Settings.LIVE_CARD_TAP_MODE, LiveCardTapMode.class, "Double tap",
                "Off", "Single tap", "Double tap");
        assertRoundTrip(Settings.LIVE_CARD_TAP_TARGET, LiveCardTapTarget.class, "Fullscreen",
                "Fullscreen", "Artwork");
    }

    @Test
    public void aiModesKeepTheirLabels() {
        assertRoundTrip(Settings.AI_PROVIDER, AiProviderChoice.class, "gemini",
                "gemini", "openai", "openrouter", "deepseek", "custom");
        assertRoundTrip(Settings.AI_DEEPSEEK_REASONING, AiDeepSeekReasoning.class, "Low",
                "Off", "Low", "High", "Max");
        assertRoundTrip(Settings.AI_TRANSLATION_MODE, AiTranslationMode.class, "On demand",
                "On demand", "Always use AI");
        assertRoundTrip(Settings.AI_PRONUNCIATION_MODE, AiPronunciationMode.class, "On demand",
                "On demand", "Always use AI");
        assertRoundTrip(Settings.AI_PRONUNCIATION_SOURCE, AiPronunciationSource.class, "Layered",
                "Layered", "AI only");
        assertRoundTrip(Settings.AI_BUTTON_BEHAVIOR, AiButtonBehavior.class,
                "Generate AI output, then toggle",
                "Generate AI output, then toggle", "Toggle display only");
    }

    @Test
    public void readingModesKeepTheirLabels() {
        assertRoundTrip(Settings.JAPANESE_READING_MODE, JapaneseReadingMode.class, "romaji_only",
                "off", "furigana_only", "furigana_romaji", "romaji_only", "cycle");
        assertRoundTrip(Settings.CHINESE_MODE, ChineseMode.class, "pinyin",
                "off", "pinyin", "jyutping", "pinyin-eza", "jyutping-eza", "cycle");
        assertRoundTrip(Settings.CYRILLIC_MODE, CyrillicMode.class, "Russian",
                "Off", "Russian", "Ukrainian", "cycle");
        assertRoundTrip(Settings.LAST_JAPANESE_CYCLE_MODE, LastJapaneseCycleMode.class,
                "romaji_only", "romaji_only", "furigana_only", "furigana_romaji");
        assertRoundTrip(Settings.LAST_CHINESE_CYCLE_MODE, LastChineseCycleMode.class,
                "pinyin", "pinyin", "jyutping");
        assertRoundTrip(Settings.LAST_CYRILLIC_CYCLE_MODE, LastCyrillicCycleMode.class,
                "Russian", "Russian", "Ukrainian");
    }

    @Test
    public void translationModesKeepTheirLabels() {
        assertRoundTrip(Settings.SOURCE_LANGUAGE_MODE, SourceLanguageMode.class, "auto",
                "auto", "manual");
        assertRoundTrip(Settings.TRANSLATION_BRIGHTNESS, TranslationBrightness.class, "Dimmed",
                "Dimmed", "Bright");
    }

    @Test
    public void retiredLabelsStillMeanTheirValue() {
        assertSame(SecondaryTextMode.TRANSLITERATION,
                StoredValues.fromStored(SecondaryTextMode.class, "Romanization"));
        assertSame(SecondaryTextMode.TRANSLITERATION,
                StoredValues.fromStored(SecondaryTextMode.class, "Romanized"));
        assertSame(LiveCardAnimation.SPOTLIGHT_WORD,
                StoredValues.fromStored(LiveCardAnimation.class, "Full"));
        assertSame(LiveCardGlow.WORD_ONLY,
                StoredValues.fromStored(LiveCardGlow.class, "Subtle line"));
    }

    @Test
    public void theProjectedFillModeIsNotAStoredValue() {
        // The spotlight card projects the word-by-word fill; the preference never holds it, so it
        // is no part of the setting's allowed values and a stored label never reads as it.
        assertFalse(LineSyncFill.WORD.isStored());
        assertEquals(Arrays.asList("Top to bottom", "Left to right (block)", "Left to right (sentence)"),
                Settings.LINE_SYNC_FILL.allowedValues);
        assertSame(LineSyncFill.TOP_TO_BOTTOM,
                StoredValues.fromStored(LineSyncFill.class, "Left to right (word)"));
    }

    @Test
    public void theSecondaryRowsFollowTheMode() {
        assertFalse(SecondaryTextMode.MAIN_ONLY.showsTransliteration());
        assertFalse(SecondaryTextMode.MAIN_ONLY.showsTranslation());
        assertTrue(SecondaryTextMode.TRANSLITERATION.showsTransliteration());
        assertFalse(SecondaryTextMode.TRANSLITERATION.showsTranslation());
        assertFalse(SecondaryTextMode.TRANSLATION.showsTransliteration());
        assertTrue(SecondaryTextMode.TRANSLATION.showsTranslation());
        assertTrue(SecondaryTextMode.BOTH.showsTransliteration());
        assertTrue(SecondaryTextMode.BOTH.showsTranslation());
    }

    @Test
    public void theTextSizeMultipliersAreUnchanged() {
        assertEquals(0.9f, TextSizeMode.SMALL.multiplier(), 0.0001f);
        assertEquals(1.0f, TextSizeMode.NORMAL.multiplier(), 0.0001f);
        assertEquals(1.2f, TextSizeMode.LARGE.multiplier(), 0.0001f);
        assertEquals(1.5f, TextSizeMode.XLARGE.multiplier(), 0.0001f);
        assertEquals("0.9", TextSizeMode.SMALL.multiplierLabel());
        assertEquals("1.0", TextSizeMode.NORMAL.multiplierLabel());
        assertEquals("1.2", TextSizeMode.LARGE.multiplierLabel());
        assertEquals("1.5", TextSizeMode.XLARGE.multiplierLabel());
    }

    @Test
    public void anUnknownWeightReadsAsMedium() {
        assertSame(TextWeight.MEDIUM, StoredValues.fromStored(TextWeight.class, "Heavy"));
        assertSame(TextWeight.MEDIUM, StoredValues.fromStored(TextWeight.class, null));
    }

    @Test
    public void aTypedReadFollowsTheStoredLabel() {
        SettingsSnapshot custom = new SettingsSnapshot(
                Collections.singletonMap(Settings.LYRICS_FONT.key, "custom"));
        assertSame(LyricsFont.CUSTOM, custom.get(Settings.LYRICS_FONT, LyricsFont.class));

        SettingsSnapshot unknown = new SettingsSnapshot(
                Collections.singletonMap(Settings.LYRICS_FONT.key, "not a font"));
        assertSame(LyricsFont.SPOTIFY, unknown.get(Settings.LYRICS_FONT, LyricsFont.class));

        SettingsSnapshot missing = new SettingsSnapshot(Collections.emptyMap());
        assertSame(LyricsFont.SPOTIFY, missing.get(Settings.LYRICS_FONT, LyricsFont.class));
    }

    /**
     * The stored labels, the default and the round trip are one contract: the setting allows
     * exactly the enum's labels, the default is the enum's default, and every label reads back as
     * the value it names - while an unknown label reads as that default.
     */
    private static <E extends Enum<E> & StoredValue> void assertRoundTrip(
            Settings.Setting<String> setting, Class<E> type, String defaultLabel, String... labels) {
        List<String> expected = Arrays.asList(labels);
        assertEquals("allowed values of " + setting.key, expected, setting.allowedValues);
        assertEquals("default of " + setting.key, defaultLabel, setting.defaultValue);
        assertEquals("labels of " + type.getSimpleName(), expected, StoredValues.labels(type));
        assertEquals("default label of " + type.getSimpleName(), defaultLabel,
                StoredValues.defaultLabel(type));
        for (E value : type.getEnumConstants()) {
            if (!value.isStored()) continue;
            assertSame("label of " + value, value, StoredValues.fromStored(type, value.storedLabel()));
            assertEquals("round trip of " + value, value.storedLabel(),
                    StoredValues.fromStored(type, value.storedLabel()).storedLabel());
        }
        assertSame("unknown label of " + type.getSimpleName(), StoredValues.defaultValue(type),
                StoredValues.fromStored(type, "not a stored label"));
        assertSame("absent label of " + type.getSimpleName(), StoredValues.defaultValue(type),
                StoredValues.fromStored(type, null));
        assertTrue("default of " + type.getSimpleName(),
                StoredValues.defaultValue(type).isDefault());
    }
}
