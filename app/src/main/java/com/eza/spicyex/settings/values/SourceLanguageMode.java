package com.eza.spicyex.settings.values;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Whether the source language is detected or chosen by hand (Settings.SOURCE_LANGUAGE_MODE). */
public enum SourceLanguageMode implements StoredValue {
    AUTO("auto"),
    MANUAL("manual");

    /** The value used when the stored label is unknown or absent. */
    public static final SourceLanguageMode DEFAULT = AUTO;

    private final String label;
    private final List<String> legacyLabels;

    SourceLanguageMode(String label, String... legacyLabels) {
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
