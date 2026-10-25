package com.eza.spicyex.settings.values;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** How a floating chip presents itself (Settings.SKIP_CHIP_STYLE, Settings.FOLLOW_CHIP_STYLE). */
public enum ChipStyle implements StoredValue {
    ICON("Icon"),
    LABEL("Label"),
    AUTO("Auto");

    /** The value used when the stored label is unknown or absent. */
    public static final ChipStyle DEFAULT = AUTO;

    private final String label;
    private final List<String> legacyLabels;

    ChipStyle(String label, String... legacyLabels) {
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
