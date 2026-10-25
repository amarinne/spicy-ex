package com.eza.spicyex.settings.values;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Where the active lyric line rests vertically (Settings.LYRICS_FOCUS_POSITION). */
public enum LyricsFocusPosition implements StoredValue {
    AUTO("Auto"),
    TOP("Top"),
    CENTER("Center"),
    BOTTOM("Bottom"),
    CUSTOM("Custom");

    /** The value used when the stored label is unknown or absent. */
    public static final LyricsFocusPosition DEFAULT = AUTO;

    private final String label;
    private final List<String> legacyLabels;

    LyricsFocusPosition(String label, String... legacyLabels) {
        this.label = label;
        this.legacyLabels = Collections.unmodifiableList(Arrays.asList(legacyLabels));
    }

    /** True when the custom percent setting owns the anchor. */
    public boolean isCustom() {
        return this == CUSTOM;
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
