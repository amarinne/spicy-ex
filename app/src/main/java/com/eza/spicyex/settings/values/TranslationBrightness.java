package com.eza.spicyex.settings.values;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** The brightness of translated rows (Settings.TRANSLATION_BRIGHTNESS). */
public enum TranslationBrightness implements StoredValue {
    DIMMED("Dimmed"),
    BRIGHT("Bright");

    /** The value used when the stored label is unknown or absent. */
    public static final TranslationBrightness DEFAULT = DIMMED;

    private final String label;
    private final List<String> legacyLabels;

    TranslationBrightness(String label, String... legacyLabels) {
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
