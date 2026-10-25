package com.eza.spicyex.settings.values;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** The lyric weight (Settings.LYRICS_WEIGHT, Settings.LIVE_CARD_WEIGHT). */
public enum TextWeight implements StoredValue {
    REGULAR("Regular"),
    MEDIUM("Medium"),
    BOLD("Bold");

    /** The value used when the stored label is unknown or absent. */
    public static final TextWeight DEFAULT = MEDIUM;

    private final String label;
    private final List<String> legacyLabels;

    TextWeight(String label, String... legacyLabels) {
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
}
