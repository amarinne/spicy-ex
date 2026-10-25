package com.eza.spicyex.settings.values;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** How the lyric source is selected (Settings.LYRICS_SOURCE_MODE). */
public enum LyricsSourceMode implements StoredValue {
    AUTO("Auto"),
    SOURCE_ORDER("Source order");

    /** The value used when the stored label is unknown or absent. */
    public static final LyricsSourceMode DEFAULT = AUTO;

    private final String label;
    private final List<String> legacyLabels;

    LyricsSourceMode(String label, String... legacyLabels) {
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
