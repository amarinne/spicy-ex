package com.eza.spicyex.settings.values;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** The readout text size (Settings.TRACK_INFO_TEXT_SIZE). */
public enum TrackInfoTextSize implements StoredValue {
    SMALL("Small"),
    NORMAL("Normal"),
    LARGE("Large"),
    XLARGE("XLarge"),
    CUSTOM("Custom");

    /** The value used when the stored label is unknown or absent. */
    public static final TrackInfoTextSize DEFAULT = NORMAL;

    private final String label;
    private final List<String> legacyLabels;

    TrackInfoTextSize(String label, String... legacyLabels) {
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
