package com.eza.spicyex.settings.values;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** The lyric animation style (Settings.ANIMATION_STYLE). */
public enum AnimationStyle implements StoredValue {
    GRADIENT_WASH("Gradient wash"),
    SPOTLIGHT("Spotlight"),
    APPLE_MUSIC("Apple Music");

    /** The value used when the stored label is unknown or absent. */
    public static final AnimationStyle DEFAULT = GRADIENT_WASH;

    private final String label;
    private final List<String> legacyLabels;

    AnimationStyle(String label, String... legacyLabels) {
        this.label = label;
        this.legacyLabels = Collections.unmodifiableList(Arrays.asList(legacyLabels));
    }

    /** True for the Apple-owned motion/blur/fade stack. */
    public boolean isApple() {
        return this == APPLE_MUSIC;
    }

    /** True when the active line or word zooms and glows instead of sweeping a fill. */
    public boolean isSpotlight() {
        return this == SPOTLIGHT;
    }

    @Override
    public String storedLabel() {
        return label;
    }

    @Override
    public boolean isDefault() {
        return this == DEFAULT;
    }

    @Override
    public List<String> legacyLabels() {
        return legacyLabels;
    }
}
