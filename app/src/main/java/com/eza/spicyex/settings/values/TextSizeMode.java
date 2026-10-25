package com.eza.spicyex.settings.values;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** The lyric text size mode (Settings.LYRICS_TEXT_SIZE, Settings.LIVE_CARD_TEXT_SIZE). */
public enum TextSizeMode implements StoredValue {
    SMALL("small"),
    NORMAL("normal"),
    LARGE("large"),
    XLARGE("xlarge"),
    CUSTOM("custom");

    /** The value used when the stored label is unknown or absent. */
    public static final TextSizeMode DEFAULT = NORMAL;

    private final String label;
    private final List<String> legacyLabels;

    TextSizeMode(String label, String... legacyLabels) {
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

    /** The size the mode renders at, before the surface's own boost. */
    public float multiplier() {
        switch (this) {
            case SMALL: return 0.9f;
            case LARGE: return 1.2f;
            case XLARGE: return 1.5f;
            default: return 1.0f;
        }
    }

    /** The multiplier as the panel shows it. */
    public String multiplierLabel() {
        switch (this) {
            case SMALL: return "0.9";
            case LARGE: return "1.2";
            case XLARGE: return "1.5";
            default: return "1.0";
        }
    }
}
