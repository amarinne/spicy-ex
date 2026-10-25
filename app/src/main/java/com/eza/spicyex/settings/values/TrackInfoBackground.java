package com.eza.spicyex.settings.values;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** What sits behind the track info readout (Settings.TRACK_INFO_BACKGROUND). */
public enum TrackInfoBackground implements StoredValue {
    GRADIENT("Gradient"),
    SOLID("Solid"),
    NONE("None"),
    BLUR("Blur");

    /** The value used when the stored label is unknown or absent. */
    public static final TrackInfoBackground DEFAULT = GRADIENT;

    private final String label;
    private final List<String> legacyLabels;

    TrackInfoBackground(String label, String... legacyLabels) {
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
