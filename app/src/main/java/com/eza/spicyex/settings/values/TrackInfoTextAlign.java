package com.eza.spicyex.settings.values;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** The vertical alignment of the readout text (Settings.TRACK_INFO_TEXT_ALIGN). */
public enum TrackInfoTextAlign implements StoredValue {
    TOP("Top"),
    CENTER("Center"),
    BOTTOM("Bottom");

    /** The value used when the stored label is unknown or absent. */
    public static final TrackInfoTextAlign DEFAULT = CENTER;

    private final String label;
    private final List<String> legacyLabels;

    TrackInfoTextAlign(String label, String... legacyLabels) {
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
