package com.eza.spicyex.settings.values;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** The readout artwork size (Settings.TRACK_INFO_ART_SIZE). */
public enum TrackInfoArtSize implements StoredValue {
    SMALL("Small"),
    NORMAL("Normal"),
    LARGE("Large"),
    CUSTOM("Custom");

    /** The value used when the stored label is unknown or absent. */
    public static final TrackInfoArtSize DEFAULT = NORMAL;

    private final String label;
    private final List<String> legacyLabels;

    TrackInfoArtSize(String label, String... legacyLabels) {
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
