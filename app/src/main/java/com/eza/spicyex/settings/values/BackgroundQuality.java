package com.eza.spicyex.settings.values;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** The render quality for the background and blur (Settings.BACKGROUND_QUALITY). */
public enum BackgroundQuality implements StoredValue {
    HIGH("high"),
    MID("mid"),
    LOW("low"),
    SUPER_LOW("superLow");

    /** The value used when the stored label is unknown or absent. */
    public static final BackgroundQuality DEFAULT = HIGH;

    private final String label;
    private final List<String> legacyLabels;

    BackgroundQuality(String label, String... legacyLabels) {
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

    /** Scales the line blur for this quality; superLow draws no blur at all. */
    public float blurMultiplier() {
        switch (this) {
            case SUPER_LOW: return 0f;
            case LOW: return 0.35f;
            case MID: return 0.70f;
            default: return 1f;
        }
    }
}
