package com.eza.spicyex.settings.values;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** The sentence spacing mode (Settings.LINE_SPACING). */
public enum LineSpacingMode implements StoredValue {
    COMPACT("compact"),
    /** The preset the release called "default". */
    DEFAULT_SPACING("default"),
    SPACIOUS("spacious"),
    MORE("more"),
    MAX("max"),
    CUSTOM("custom");

    /** The value used when the stored label is unknown or absent. */
    public static final LineSpacingMode DEFAULT = SPACIOUS;

    private final String label;
    private final List<String> legacyLabels;

    LineSpacingMode(String label, String... legacyLabels) {
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
