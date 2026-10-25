package com.eza.spicyex.settings.registry;

import com.eza.spicyex.Settings;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.*;

public class ControlRegistryTest {
    @Test public void ownsEveryCurrentEditorSettingExactlyOnce() throws Exception {
        String source = controllerSource();
        int start = source.indexOf("TOUCHED_SETTINGS = {");
        String touched = source.substring(start, source.indexOf("};", start));
        Set<Settings.Setting<?>> expected = new HashSet<>();
        Matcher names = Pattern.compile("Settings\\.([A-Z_]+)").matcher(touched);
        while (names.find()) expected.add((Settings.Setting<?>) Settings.class.getField(names.group(1)).get(null));
        Matcher writes = Pattern.compile("(?:writer\\.put|\\bput)\\(Settings\\.([A-Z_]+)\\s*,").matcher(source);
        while (writes.find()) expected.add((Settings.Setting<?>) Settings.class.getField(writes.group(1)).get(null));
        assertEquals("The editor owns its baseline settings and the two background effect controls", 63, expected.size());
        Set<Settings.Setting<?>> actual = new HashSet<>();
        for (ControlSpec row : ControlRegistry.editorControls()) {
            assertEquals(ControlSpec.Surface.EDITOR, row.surface);
            assertNotNull(row.element);
            for (Settings.Setting<?> setting : Arrays.asList(row.setting, row.customValue)) {
                if (setting != null) assertTrue("Duplicate editor ownership: " + setting.key, actual.add(setting));
            }
            if (!row.choices.isEmpty()) {
                assertNotNull(row.setting.allowedValues);
                assertTrue(row.setting.key, row.setting.allowedValues.containsAll(row.choices));
            }
        }
        assertEquals(expected, actual);
    }

    @Test public void integerSliderMetadataComesFromItsSettingAndStringSlidersHaveCodecs() {
        for (ControlSpec row : ControlRegistry.editorControls()) {
            if (row.slider == null) continue;
            Settings.Setting<?> numeric = row.kind == ControlSpec.Kind.MODE_SLIDER ? row.customValue : row.setting;
            if (numeric instanceof Settings.IntegerSetting) {
                Settings.IntegerSetting setting = (Settings.IntegerSetting) numeric;
                assertEquals(setting.minValue, row.slider.min);
                assertEquals(setting.maxValue, row.slider.max);
                assertEquals(setting.stepValue, row.slider.step);
                assertEquals(setting.defaultValue, row.slider.defaultMarker);
                assertNull(row.slider.codec);
            } else {
                assertTrue(numeric instanceof Settings.StringSetting);
                assertNotNull(row.slider.codec);
            }
        }
    }

    @Test public void snapshotAndRegistryCollectionsAreImmutable() {
        Map<String, Object> values = new HashMap<>();
        values.put(Settings.TRACK_INFO_TEXT_SIZE_ADAPTIVE.key, true);
        SettingsSnapshot snapshot = new SettingsSnapshot(values);
        values.clear();
        assertTrue(snapshot.get(Settings.TRACK_INFO_TEXT_SIZE_ADAPTIVE));
        assertEquals(Settings.LYRICS_FONT.defaultValue, snapshot.get(Settings.LYRICS_FONT));
        assertThrows(UnsupportedOperationException.class, () -> ControlRegistry.editorControls().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> ControlRegistry.editorControls(ControlSpec.Element.ARTWORK).clear());
        for (ControlSpec row : ControlRegistry.editorControls()) {
            assertThrows(UnsupportedOperationException.class, () -> row.choices.add("unexpected"));
        }
    }

    @Test public void tabSelectionRequiresATabbedElementAndKeepsStyleOrder() {
        UiContext context = new UiContext(false, false, false,
                new SettingsSnapshot(Collections.emptyMap()), false);
        List<ControlSpec> style = ControlRegistry.tabControls(ControlSpec.Element.LYRICS, 0, context);
        assertEquals(Arrays.asList(Settings.LYRICS_TEXT_SIZE.key, Settings.LYRICS_ADAPTIVE_TEXT_SIZE.key,
                Settings.ADAPTIVE_SECTIONING.key, Settings.LYRICS_FONT.key, Settings.LYRICS_WEIGHT.key,
                Settings.LINE_SPACING.key), keys(style));
        for (ControlSpec row : style) assertEquals(Integer.valueOf(0), row.tab);
        assertThrows(IllegalArgumentException.class,
                () -> ControlRegistry.tabControls(ControlSpec.Element.ARTWORK, 0, context));
        assertThrows(IllegalArgumentException.class,
                () -> ControlRegistry.tabControls(ControlSpec.Element.LYRICS, 3, context));
    }

    /** The Animation tab keeps the shared rows first, then either the Apple rows or the
     *  word-bounce rows, exactly as the hand-built panel did. */
    @Test public void animationTabKeepsItsOrderAndTheAppleRowsCarryTheirOwnLabel() {
        UiContext other = new UiContext(false, false, false,
                new SettingsSnapshot(Collections.emptyMap()), false);
        assertEquals(Arrays.asList(Settings.ANIMATION_STYLE.key, Settings.LOAD_LIFT_ANIMATION.key,
                Settings.WORD_BOUNCE.key, Settings.WORD_BOUNCE_STYLE.key, Settings.LINE_SYNC_FILL.key),
                keys(ControlRegistry.tabControls(ControlSpec.Element.LYRICS, 1, other)));

        Map<String, Object> apple = new HashMap<>();
        apple.put(Settings.ANIMATION_STYLE.key, "Apple Music");
        UiContext appleContext = new UiContext(false, false, false, new SettingsSnapshot(apple), false);
        List<ControlSpec> appleRows = ControlRegistry.tabControls(ControlSpec.Element.LYRICS, 1, appleContext);
        assertEquals(Arrays.asList(Settings.ANIMATION_STYLE.key, Settings.LOAD_LIFT_ANIMATION.key,
                Settings.LINE_SLIDE_ANIMATION.key, Settings.APPLE_LIFT.key,
                Settings.APPLE_FADE_PASSED_LINES.key, Settings.APPLE_CASCADE_SPEED.key,
                Settings.APPLE_SPRING_STRENGTH.key), keys(appleRows));
        for (ControlSpec row : appleRows) {
            boolean appleSlider = row.setting == Settings.APPLE_CASCADE_SPEED
                    || row.setting == Settings.APPLE_SPRING_STRENGTH;
            assertEquals("Only the Apple sliders carry their own title", appleSlider,
                    row.labelKey != null);
        }
    }

    /** The Effects tab keeps the interlude icon, the glow toggle and the blur control. */
    @Test public void effectsTabKeepsItsOrder() {
        UiContext context = new UiContext(false, false, false,
                new SettingsSnapshot(Collections.emptyMap()), false);
        List<ControlSpec> effects = ControlRegistry.tabControls(ControlSpec.Element.LYRICS, 2, context);
        assertEquals(Arrays.asList(Settings.INTERLUDE_ICON.key, Settings.ENABLE_GLOW_BLUR.key,
                Settings.LYRICS_BLUR_STRENGTH.key), keys(effects));
        assertEquals("distant_line_blur", effects.get(2).compositeId);
        assertEquals(ControlSpec.Kind.COMPOSITE, effects.get(2).kind);
    }

    /** The Background element keeps its order: the style chips and the one darkening slider. */
    @Test public void backgroundKeepsItsOrderAndItsDarkeningIsOneSlider() {
        UiContext gradient = new UiContext(false, false, false,
                new SettingsSnapshot(Collections.emptyMap()), false);
        List<ControlSpec> rows = ControlRegistry.optionControls(ControlSpec.Element.BACKGROUND, gradient);
        assertEquals(Arrays.asList(Settings.BACKGROUND_STYLE.key, Settings.LYRICS_DARKENING.key),
                keys(rows));

        Map<String, Object> animated = new HashMap<>();
        animated.put(Settings.BACKGROUND_STYLE.key, "Animated texture");
        UiContext animatedContext = new UiContext(false, false, false, new SettingsSnapshot(animated), false);
        List<ControlSpec> animatedRows = ControlRegistry.optionControls(
                ControlSpec.Element.BACKGROUND, animatedContext);
        assertEquals(Arrays.asList(Settings.BACKGROUND_STYLE.key, Settings.BACKGROUND_EFFECT.key, Settings.LYRICS_DARKENING.key),
                keys(animatedRows));
        assertEquals(ControlSpec.Kind.SLIDER, animatedRows.get(2).kind);
        assertEquals("One control writes one value: 0 is off",
                null, animatedRows.get(2).customValue);
    }

    @Test public void backgroundEffectIsOneCompactSearchablePicker() {
        Map<String, Object> animated = new HashMap<>();
        animated.put(Settings.BACKGROUND_STYLE.key, "Animated texture");
        UiContext context = new UiContext(false, false, false,
                new SettingsSnapshot(animated), false, true);
        ControlSpec effect = ControlRegistry.optionControls(
                ControlSpec.Element.BACKGROUND, context).get(1);
        assertEquals(Settings.BACKGROUND_EFFECT, effect.setting);
        assertEquals(ControlSpec.Kind.COMPOSITE, effect.kind);
        assertEquals("background_effect_picker", effect.compositeId);
        assertEquals(ControlSpec.Effect.BACKGROUND, effect.effect);
        assertTrue(effect.choices.isEmpty());
    }

    @Test public void randomPoolOnlyShowsForRandomOnSupportedTextureBackgrounds() {
        Map<String, Object> settings = new HashMap<>();
        settings.put(Settings.BACKGROUND_STYLE.key, "Static texture");
        settings.put(Settings.BACKGROUND_EFFECT.key, "Random");
        assertEquals(Arrays.asList(Settings.BACKGROUND_STYLE.key, Settings.BACKGROUND_EFFECT.key,
                Settings.BACKGROUND_RANDOM_EFFECTS.key, Settings.LYRICS_DARKENING.key),
                keys(ControlRegistry.optionControls(ControlSpec.Element.BACKGROUND,
                        new UiContext(false, false, false, new SettingsSnapshot(settings), false, true))));
        assertEquals(Arrays.asList(Settings.BACKGROUND_STYLE.key, Settings.BACKGROUND_EFFECT.key,
                Settings.BACKGROUND_RANDOM_EFFECTS.key, Settings.LYRICS_DARKENING.key),
                keys(ControlRegistry.optionControls(ControlSpec.Element.BACKGROUND,
                        new UiContext(false, false, false, new SettingsSnapshot(settings), false, false))));
    }

    /** The Top bar keeps its order, and every row refreshes the chrome it changed. */
    @Test public void topBarKeepsItsOrderAndRefreshesTheChrome() {
        UiContext context = new UiContext(false, false, false,
                new SettingsSnapshot(Collections.emptyMap()), false);
        List<ControlSpec> rows = ControlRegistry.optionControls(ControlSpec.Element.TOP_BAR, context);
        assertEquals(Arrays.asList(Settings.SHOW_FULLSCREEN_BACK_BUTTON.key, Settings.LIKED_SONGS_BUTTON.key,
                Settings.CHROME_CLUSTER_POSITION.key, Settings.CHROME_CLUSTER_LAYOUT.key,
                Settings.FULLSCREEN_CONTROLS.key), keys(rows));
        for (ControlSpec row : rows) {
            if (row.setting == Settings.FULLSCREEN_CONTROLS) continue;
            assertEquals(ControlSpec.Effect.CHROME, row.effect);
        }
    }

    /** The timeout slider is a stored name, not a number: the codec names every stop and the
     *  shell re-applies its settings, because the value is one the shell reads as it draws. */
    @Test public void fullscreenControlsIsACodedSliderThatTheShellReapplies() {
        UiContext context = new UiContext(false, false, false,
                new SettingsSnapshot(Collections.emptyMap()), false);
        List<ControlSpec> rows = ControlRegistry.optionControls(ControlSpec.Element.TOP_BAR, context);
        ControlSpec timeout = rows.get(rows.size() - 1);
        assertEquals(Settings.FULLSCREEN_CONTROLS.key, timeout.setting.key);
        assertEquals(ControlSpec.Kind.SLIDER, timeout.kind);
        assertEquals(ControlSpec.Effect.APPLY_PREFERENCES, timeout.effect);
        assertSame(ControlRegistry.FULLSCREEN_CONTROLS_CODEC, timeout.slider.codec);
        assertEquals(Integer.valueOf(31), timeout.slider.endValue);
    }

    /** The chips keep their order: skip first, then follow. Every row names itself inside the
     *  element's card and refreshes both chip captures when it changes. */
    @Test public void chipsKeepTheirOrderAndNameThemselves() {
        UiContext context = new UiContext(false, false, false,
                new SettingsSnapshot(Collections.emptyMap()), false);
        List<ControlSpec> rows = ControlRegistry.optionControls(ControlSpec.Element.CHIPS, context);
        assertEquals(Arrays.asList(Settings.SKIP_CHIP_POSITION.key, Settings.SKIP_CHIP_STYLE.key,
                Settings.FOLLOW_CHIP_POSITION.key, Settings.FOLLOW_CHIP_STYLE.key,
                Settings.FOLLOW_CHIP_ICON.key), keys(rows));
        for (ControlSpec row : rows) {
            assertEquals(ControlSpec.Effect.CHIPS, row.effect);
            assertEquals("settings_label_" + row.setting.key, row.labelKey);
        }
        assertEquals("settings_layout_editor_element_skip", rows.get(0).groupTitleKey);
        assertEquals("settings_layout_editor_element_follow", rows.get(4).groupTitleKey);
    }

    /**
     * The card keeps its two tabs: the Text tab's rows, then the Animation tab's rows. The
     * scroll scope appears only when the overflow scrolls with the lyric.
     */
    @Test public void cardTabsKeepTheirOrderAndTheirConditionalRow() {
        UiContext context = new UiContext(false, false, false,
                new SettingsSnapshot(Collections.emptyMap()), false);
        assertEquals(Arrays.asList(Settings.LIVE_CARD_TEXT_SIZE.key, Settings.LIVE_CARD_WEIGHT.key,
                Settings.LIVE_CARD_SECONDARY_MODE.key, Settings.LIVE_CARD_OVERFLOW.key),
                keys(ControlRegistry.tabControls(ControlSpec.Element.CARD, 0, context)));
        assertEquals(Arrays.asList(Settings.LIVE_CARD_ANIMATION.key, Settings.LIVE_CARD_GLOW.key,
                Settings.LIVE_CARD_LINE_SYNC_FILL.key, Settings.LIVE_CARD_TRANSITION.key),
                keys(ControlRegistry.tabControls(ControlSpec.Element.CARD, 1, context)));

        Map<String, Object> scrolling = new HashMap<>();
        scrolling.put(Settings.LIVE_CARD_OVERFLOW.key, "Scroll with lyric");
        UiContext scrollingContext = new UiContext(false, false, false, new SettingsSnapshot(scrolling), false);
        assertEquals(Arrays.asList(Settings.LIVE_CARD_TEXT_SIZE.key, Settings.LIVE_CARD_WEIGHT.key,
                Settings.LIVE_CARD_SECONDARY_MODE.key, Settings.LIVE_CARD_OVERFLOW.key,
                Settings.LIVE_CARD_SCROLL_SCOPE.key),
                keys(ControlRegistry.tabControls(ControlSpec.Element.CARD, 0, scrollingContext)));
    }

    /**
     * The card's text size is one control that writes the mode and its custom value, and it
     * marks the card preview dirty. Every other card row rebuilds the panel, so the scroll
     * scope can follow the overflow.
     */
    @Test public void cardTextSizeWritesBothKeysAndMarksTheCard() {
        UiContext context = new UiContext(false, false, false,
                new SettingsSnapshot(Collections.emptyMap()), false);
        ControlSpec size = null;
        for (ControlSpec row : ControlRegistry.tabControls(ControlSpec.Element.CARD, 0, context)) {
            if (row.setting == Settings.LIVE_CARD_TEXT_SIZE) size = row;
        }
        assertNotNull("The card text size must have one registry entry", size);
        assertEquals(ControlSpec.Kind.MODE_SLIDER, size.kind);
        assertEquals(Settings.LIVE_CARD_TEXT_SIZE_CUSTOM, size.customValue);
        assertEquals("custom", size.modeValue);
        assertEquals(ControlSpec.Effect.CARD, size.effect);
        for (ControlSpec row : ControlRegistry.tabControls(ControlSpec.Element.CARD, 0, context)) {
            if (row == size) continue;
            assertEquals(ControlSpec.Effect.REBUILD, row.effect);
        }
        for (ControlSpec row : ControlRegistry.tabControls(ControlSpec.Element.CARD, 1, context)) {
            assertEquals(ControlSpec.Effect.REBUILD, row.effect);
        }
    }

    private static List<String> keys(List<ControlSpec> rows) {
        List<String> keys = new java.util.ArrayList<>();
        for (ControlSpec row : rows) keys.add(row.setting == null ? "@" + row.compositeId : row.setting.key);
        return keys;
    }

    /** The editor session's own source: the settings it owns and the ones it writes. */
    private static String controllerSource() throws Exception {
        return new String(Files.readAllBytes(Paths.get(
                "src/main/java/com/eza/spicyex/hooks/LyricsLayoutEditController.java")),
                StandardCharsets.UTF_8);
    }

    @Test public void lyricsFontCompositeOffersAllThreeChips() {
        ControlSpec font = null;
        for (ControlSpec row : ControlRegistry.editorControls(ControlSpec.Element.LYRICS)) {
            if (row.setting == Settings.LYRICS_FONT) font = row;
        }
        assertNotNull("Lyrics font must have one registry entry", font);
        assertEquals(ControlSpec.Kind.COMPOSITE, font.kind);
        assertEquals("font_chips", font.compositeId);
        assertEquals(Arrays.asList("spotify", "apple", "custom"), font.choices);
        UiContext context = new UiContext(false, false, false,
                new SettingsSnapshot(Collections.emptyMap()), false);
        assertTrue(ControlRegistry.tabControls(ControlSpec.Element.LYRICS, 0, context).contains(font));
    }

    @Test public void invalidOfferedValuesAndMissingStringSliderCodecAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ControlSpec(Settings.TRACK_INFO_POSITION,
                ControlSpec.Surface.EDITOR, ControlSpec.Element.ARTWORK, null, ControlSpec.Kind.CHOICE,
                Arrays.asList("Side"), null, null, ControlSpec.Effect.NONE, null, null, null, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new ControlSpec(Settings.FULLSCREEN_CONTROLS,
                ControlSpec.Surface.EDITOR, ControlSpec.Element.TOP_BAR, null, ControlSpec.Kind.SLIDER,
                Collections.emptyList(), null, null, ControlSpec.Effect.NONE, null, null,
                new SliderSpec(1, 30, 1, null, null, null, null, null), null, null, null, null));
    }
}
