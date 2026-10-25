package com.eza.spicyex.settings.values;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Where the track info readout sits (Settings.TRACK_INFO_POSITION). */
public enum TrackInfoPosition implements StoredValue {
    OFF("Off"),
    TOP("Top"),
    BOTTOM("Bottom"),
    HEADER("Header");

    /** The value used when the stored label is unknown or absent. */
    public static final TrackInfoPosition DEFAULT = OFF;

    private final String label;
    private final List<String> legacyLabels;

    TrackInfoPosition(String label, String... legacyLabels) {
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
