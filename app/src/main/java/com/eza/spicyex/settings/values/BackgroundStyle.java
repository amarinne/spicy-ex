package com.eza.spicyex.settings.values;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** The lyrics background (Settings.BACKGROUND_STYLE). */
public enum BackgroundStyle implements StoredValue {
    GRADIENT("Gradient"),
    STATIC_TEXTURE("Static texture"),
    ANIMATED_TEXTURE("Animated texture");

    /** The value used when the stored label is unknown or absent. */
    public static final BackgroundStyle DEFAULT = GRADIENT;

    private final String label;
    private final List<String> legacyLabels;

    BackgroundStyle(String label, String... legacyLabels) {
        this.label = label;
        this.legacyLabels = Collections.unmodifiableList(Arrays.asList(legacyLabels));
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

    /** A texture background replaces the gradient; the plain gradient draws no texture. */
    public boolean usesTexture() {
        return this != GRADIENT;
    }

    /** Only the animated texture moves. */
    public boolean isAnimated() {
        return this == ANIMATED_TEXTURE;
    }
}
