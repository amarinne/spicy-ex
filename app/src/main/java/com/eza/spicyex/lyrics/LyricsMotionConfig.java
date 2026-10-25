package com.eza.spicyex.lyrics;

import com.eza.spicyex.Settings;
import com.eza.spicyex.settings.SettingsReader;
import com.eza.spicyex.settings.values.AnimationStyle;
import com.eza.spicyex.settings.values.LyricsFocusPosition;

/**
 * Immutable motion, animation, and focus snapshot: the shell and renderer settings read together.
 *
 * <p>Owns no schema, default, persistence, cache identity, or live observation. The reader
 * resolves orientation, never this holder. Consumers take a fresh snapshot where they read the
 * settings before.
 */
public final class LyricsMotionConfig {
    public final AnimationStyle animationStyle;
    public final boolean lineSlideAnimation;
    public final boolean loadLiftAnimation;
    public final int appleCascadeSpeed;
    public final int appleSpringStrength;
    public final boolean appleFadePassedLines;
    public final boolean appleCompactTextEnabled;
    public final LyricsFocusPosition focusPosition;
    public final int focusCustomPercent;
    public final boolean adaptiveLandscapeLayout;
    public final String transitionFeel;

    private static final SettingsReader DEFAULTS = new SettingsReader() {
        @Override
        public <T> T get(Settings.Setting<T> setting) {
            return setting.defaultValue;
        }
    };

    private LyricsMotionConfig(AnimationStyle animationStyle, boolean lineSlideAnimation,
            boolean loadLiftAnimation, int appleCascadeSpeed, int appleSpringStrength,
            boolean appleFadePassedLines, boolean appleCompactTextEnabled,
            LyricsFocusPosition focusPosition, int focusCustomPercent,
            boolean adaptiveLandscapeLayout, String transitionFeel) {
        this.animationStyle = animationStyle;
        this.lineSlideAnimation = lineSlideAnimation;
        this.loadLiftAnimation = loadLiftAnimation;
        this.appleCascadeSpeed = appleCascadeSpeed;
        this.appleSpringStrength = appleSpringStrength;
        this.appleFadePassedLines = appleFadePassedLines;
        this.appleCompactTextEnabled = appleCompactTextEnabled;
        this.focusPosition = focusPosition;
        this.focusCustomPercent = focusCustomPercent;
        this.adaptiveLandscapeLayout = adaptiveLandscapeLayout;
        this.transitionFeel = transitionFeel;
    }

    /** A null reader reads every schema default, like an absent config elsewhere. */
    public static LyricsMotionConfig read(SettingsReader reader) {
        SettingsReader source = reader == null ? DEFAULTS : reader;
        return new LyricsMotionConfig(
                source.get(Settings.ANIMATION_STYLE, AnimationStyle.class),
                Boolean.TRUE.equals(source.get(Settings.LINE_SLIDE_ANIMATION)),
                Boolean.TRUE.equals(source.get(Settings.LOAD_LIFT_ANIMATION)),
                source.get(Settings.APPLE_CASCADE_SPEED),
                source.get(Settings.APPLE_SPRING_STRENGTH),
                Boolean.TRUE.equals(source.get(Settings.APPLE_FADE_PASSED_LINES)),
                Boolean.TRUE.equals(source.get(Settings.APPLE_COMPACT_TEXT)),
                source.get(Settings.LYRICS_FOCUS_POSITION, LyricsFocusPosition.class),
                source.get(Settings.LYRICS_FOCUS_POSITION_CUSTOM_PERCENT),
                Boolean.TRUE.equals(source.get(Settings.ADAPTIVE_LANDSCAPE_LAYOUT)),
                source.get(Settings.TRANSITION_FEEL));
    }

    public boolean isApple() {
        return animationStyle.isApple();
    }

    public boolean isSpotlight() {
        return animationStyle.isSpotlight();
    }

    public boolean appleDimPassed() {
        return isApple() && appleFadePassedLines;
    }

    public boolean appleCompactText() {
        return isApple() && appleCompactTextEnabled;
    }

    /** The same snapshot projected onto a card surface: no Apple stack, optional spotlight. */
    LyricsMotionConfig forLiveCard(boolean spotlight) {
        return new LyricsMotionConfig(
                spotlight ? AnimationStyle.SPOTLIGHT : AnimationStyle.GRADIENT_WASH,
                false,
                false,
                appleCascadeSpeed,
                appleSpringStrength,
                false,
                false,
                focusPosition,
                focusCustomPercent,
                adaptiveLandscapeLayout,
                transitionFeel);
    }
}
