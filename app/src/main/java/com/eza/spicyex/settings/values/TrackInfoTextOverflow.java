package com.eza.spicyex.settings.values;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** How the readout text behaves when it does not fit (Settings.TRACK_INFO_TEXT_OVERFLOW). */
public enum TrackInfoTextOverflow implements StoredValue {
    CLIP("Clip"),
    WRAP("Wrap"),
    SCROLL("Scroll");

    /** The value used when the stored label is unknown or absent. */
    public static final TrackInfoTextOverflow DEFAULT = WRAP;

    private final String label;
    private final List<String> legacyLabels;

    TrackInfoTextOverflow(String label, String... legacyLabels) {
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
