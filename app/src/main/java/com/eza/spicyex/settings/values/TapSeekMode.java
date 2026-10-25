package com.eza.spicyex.settings.values;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** How a tap on a lyric line seeks to it (Settings.TAP_SEEK_MODE). */
public enum TapSeekMode implements StoredValue {
    OFF("Off"),
    SINGLE_TAP("Single tap"),
    DOUBLE_TAP("Double tap");

    /** The value used when the stored label is unknown or absent. */
    public static final TapSeekMode DEFAULT = DOUBLE_TAP;

    private final String label;
    private final List<String> legacyLabels;

    TapSeekMode(String label, String... legacyLabels) {
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
