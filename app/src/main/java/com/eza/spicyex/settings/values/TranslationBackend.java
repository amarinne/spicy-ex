package com.eza.spicyex.settings.values;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Which provider serves the Meaning layer (Settings.TRANSLATION_BACKEND). */
public enum TranslationBackend implements StoredValue {
    GOOGLE_UNOFFICIAL("google_unofficial"),
    PROVIDER("provider");

    /** The value used when the stored label is unknown or absent. */
    public static final TranslationBackend DEFAULT = GOOGLE_UNOFFICIAL;

    private final String label;
    private final List<String> legacyLabels;

    TranslationBackend(String label, String... legacyLabels) {
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
