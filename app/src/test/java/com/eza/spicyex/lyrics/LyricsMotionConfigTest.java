package com.eza.spicyex.lyrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.eza.spicyex.Settings;
import com.eza.spicyex.settings.SettingsReader;
import com.eza.spicyex.settings.values.AnimationStyle;
import com.eza.spicyex.settings.values.LyricsFocusPosition;
import com.eza.spicyex.settings.values.StoredValues;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/** The motion snapshot: every field, schema defaults, projections, and the composed render config. */
public class LyricsMotionConfigTest {
    @Test
    public void readsEveryMotionSettingOnce() {
        FakeStore store = new FakeStore();
        store.set(Settings.ANIMATION_STYLE, "Apple Music");
        store.set(Settings.LINE_SLIDE_ANIMATION, true);
        store.set(Settings.LOAD_LIFT_ANIMATION, true);
        store.set(Settings.APPLE_CASCADE_SPEED, 150);
        store.set(Settings.APPLE_SPRING_STRENGTH, 175);
        store.set(Settings.APPLE_FADE_PASSED_LINES, true);
        store.set(Settings.APPLE_COMPACT_TEXT, true);
        store.set(Settings.LYRICS_FOCUS_POSITION, "Custom");
        store.set(Settings.LYRICS_FOCUS_POSITION_CUSTOM_PERCENT, 35);
        store.set(Settings.ADAPTIVE_LANDSCAPE_LAYOUT, false);
        store.set(Settings.TRANSITION_FEEL, "Relaxed");

        LyricsMotionConfig motion = LyricsMotionConfig.read(store);

        assertSame(AnimationStyle.APPLE_MUSIC, motion.animationStyle);
        assertTrue(motion.lineSlideAnimation);
        assertTrue(motion.loadLiftAnimation);
        assertEquals(150, motion.appleCascadeSpeed);
        assertEquals(175, motion.appleSpringStrength);
        assertTrue(motion.appleFadePassedLines);
        assertTrue(motion.appleCompactTextEnabled);
        assertSame(LyricsFocusPosition.CUSTOM, motion.focusPosition);
        assertEquals(35, motion.focusCustomPercent);
        assertFalse(motion.adaptiveLandscapeLayout);
        assertEquals("Relaxed", motion.transitionFeel);
        assertEquals(11, store.reads);
    }

    @Test
    public void unsetValuesKeepTheirSchemaDefaults() {
        LyricsMotionConfig motion = LyricsMotionConfig.read(new FakeStore());

        assertSame(StoredValues.defaultValue(AnimationStyle.class), motion.animationStyle);
        assertEquals(Settings.LINE_SLIDE_ANIMATION.defaultValue,
                Boolean.valueOf(motion.lineSlideAnimation));
        assertEquals(Settings.LOAD_LIFT_ANIMATION.defaultValue,
                Boolean.valueOf(motion.loadLiftAnimation));
        assertEquals(Settings.APPLE_CASCADE_SPEED.defaultValue,
                Integer.valueOf(motion.appleCascadeSpeed));
        assertEquals(Settings.APPLE_SPRING_STRENGTH.defaultValue,
                Integer.valueOf(motion.appleSpringStrength));
        assertEquals(Settings.APPLE_FADE_PASSED_LINES.defaultValue,
                Boolean.valueOf(motion.appleFadePassedLines));
        assertEquals(Settings.APPLE_COMPACT_TEXT.defaultValue,
                Boolean.valueOf(motion.appleCompactTextEnabled));
        assertSame(StoredValues.defaultValue(LyricsFocusPosition.class), motion.focusPosition);
        assertEquals(Settings.LYRICS_FOCUS_POSITION_CUSTOM_PERCENT.defaultValue,
                Integer.valueOf(motion.focusCustomPercent));
        assertEquals(Settings.ADAPTIVE_LANDSCAPE_LAYOUT.defaultValue,
                Boolean.valueOf(motion.adaptiveLandscapeLayout));
        assertEquals(Settings.TRANSITION_FEEL.defaultValue, motion.transitionFeel);
    }

    @Test
    public void orientationIsWhicheverValueTheReaderResolves() {
        FakeStore portrait = new FakeStore();
        portrait.set(Settings.ADAPTIVE_LANDSCAPE_LAYOUT, false);
        assertFalse(LyricsMotionConfig.read(portrait).adaptiveLandscapeLayout);

        FakeStore landscape = new FakeStore();
        landscape.set(Settings.ADAPTIVE_LANDSCAPE_LAYOUT, true);
        assertTrue(LyricsMotionConfig.read(landscape).adaptiveLandscapeLayout);
    }

    @Test
    public void unknownLabelsReadAsTheValueDefault() {
        FakeStore store = new FakeStore();
        store.set(Settings.ANIMATION_STYLE, "Not a style");
        store.set(Settings.LYRICS_FOCUS_POSITION, "Not a focus point");

        LyricsMotionConfig motion = LyricsMotionConfig.read(store);

        assertSame(AnimationStyle.GRADIENT_WASH, motion.animationStyle);
        assertSame(LyricsFocusPosition.AUTO, motion.focusPosition);
    }

    @Test
    public void snapshotDoesNotFollowLaterWrites() {
        FakeStore store = new FakeStore();
        store.set(Settings.ANIMATION_STYLE, "Spotlight");
        store.set(Settings.LYRICS_FOCUS_POSITION, "Bottom");

        LyricsMotionConfig motion = LyricsMotionConfig.read(store);

        store.set(Settings.ANIMATION_STYLE, "Apple Music");
        store.set(Settings.LYRICS_FOCUS_POSITION, "Top");
        assertSame(AnimationStyle.SPOTLIGHT, motion.animationStyle);
        assertSame(LyricsFocusPosition.BOTTOM, motion.focusPosition);
    }

    @Test
    public void appleProjectionsFollowTheAnimationStyle() {
        FakeStore apple = new FakeStore();
        apple.set(Settings.ANIMATION_STYLE, "Apple Music");
        apple.set(Settings.APPLE_FADE_PASSED_LINES, true);
        apple.set(Settings.APPLE_COMPACT_TEXT, true);
        LyricsMotionConfig appleMotion = LyricsMotionConfig.read(apple);
        assertTrue(appleMotion.isApple());
        assertFalse(appleMotion.isSpotlight());
        assertTrue(appleMotion.appleDimPassed());
        assertTrue(appleMotion.appleCompactText());

        FakeStore wash = new FakeStore();
        wash.set(Settings.ANIMATION_STYLE, "Gradient wash");
        wash.set(Settings.APPLE_FADE_PASSED_LINES, true);
        wash.set(Settings.APPLE_COMPACT_TEXT, true);
        LyricsMotionConfig washMotion = LyricsMotionConfig.read(wash);
        assertFalse(washMotion.isApple());
        assertFalse(washMotion.appleDimPassed());
        assertFalse(washMotion.appleCompactText());

        FakeStore spotlight = new FakeStore();
        spotlight.set(Settings.ANIMATION_STYLE, "Spotlight");
        assertTrue(LyricsMotionConfig.read(spotlight).isSpotlight());
    }

    @Test
    public void liveCardProjectionDropsTheAppleStackAndKeepsTheRequestedSpotlight() {
        FakeStore store = new FakeStore();
        store.set(Settings.ANIMATION_STYLE, "Apple Music");
        store.set(Settings.APPLE_FADE_PASSED_LINES, true);
        store.set(Settings.APPLE_COMPACT_TEXT, true);
        LyricsMotionConfig motion = LyricsMotionConfig.read(store);

        LyricsMotionConfig spotlight = motion.forLiveCard(true);
        assertTrue(spotlight.isSpotlight());
        assertFalse(spotlight.isApple());
        assertFalse(spotlight.appleDimPassed());
        assertFalse(spotlight.appleCompactText());

        LyricsMotionConfig wash = motion.forLiveCard(false);
        assertFalse(wash.isSpotlight());
        assertFalse(wash.isApple());
        assertFalse(wash.appleDimPassed());
        assertFalse(wash.appleCompactText());
    }

    @Test
    public void composedRenderConfigMirrorsTheMotionSnapshot() {
        LyricsRenderConfig render = LyricsRenderConfig.read(null, null);
        LyricsMotionConfig motion = render.motion;

        assertSame(StoredValues.defaultValue(AnimationStyle.class), motion.animationStyle);
        assertEquals(motion.isSpotlight(), render.spotlight);
        assertEquals(motion.isApple(), render.appleStyle);
        assertEquals(motion.appleDimPassed(), render.appleDimPassed);
        assertEquals(motion.appleCompactText(), render.appleCompactText);
        assertEquals(Settings.TRANSITION_FEEL.defaultValue, motion.transitionFeel);

        LyricsRenderConfig card = render.forLiveCard();
        assertFalse(card.motion.isApple());
        assertFalse(card.motion.isSpotlight());
        assertFalse(card.appleLift);
        assertFalse(card.appleDimPassed);
        assertFalse(card.appleCompactText);

        LyricsRenderConfig auto = render.forAndroidAuto();
        assertFalse(auto.motion.isApple());
        assertFalse(auto.motion.isSpotlight());
        assertFalse(auto.appleLift);
    }

    private static final class FakeStore implements SettingsReader {
        final Map<String, Object> values = new HashMap<>();
        int reads;

        void set(Settings.Setting<?> setting, Object value) {
            values.put(setting.key, value);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T get(Settings.Setting<T> setting) {
            reads++;
            Object value = values.get(setting.key);
            return value == null ? setting.defaultValue : (T) value;
        }
    }
}
