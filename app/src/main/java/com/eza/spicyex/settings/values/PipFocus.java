package com.eza.spicyex.settings.values;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Where the current line rests in the picture-in-picture window (Settings.PIP_FOCUS). */
public enum PipFocus implements StoredValue {
    SAME_AS_LYRICS_SCREEN("Same as lyrics screen"),
    CENTER("Center");

    /** The value used when the stored label is unknown or absent. */
    public static final PipFocus DEFAULT = SAME_AS_LYRICS_SCREEN;

    private final String label;
    private final List<String> legacyLabels;

    PipFocus(String label, String... legacyLabels) {
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
