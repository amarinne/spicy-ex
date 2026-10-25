package com.eza.spicyex.settings.values;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Which orientations hide the status bar on the lyrics screen (Settings.STATUS_BAR_HIDDEN_MODE). */
public enum StatusBarHiddenMode implements StoredValue {
    OFF("Off"),
    PORTRAIT("Portrait"),
    LANDSCAPE("Landscape"),
    BOTH("Both");

    /** The value used when the stored label is unknown or absent. */
    public static final StatusBarHiddenMode DEFAULT = OFF;

    private final String label;
    private final List<String> legacyLabels;

    StatusBarHiddenMode(String label, String... legacyLabels) {
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
