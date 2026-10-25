package com.eza.spicyex.settings.values;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** When AI pronunciation runs (Settings.AI_PRONUNCIATION_MODE). */
public enum AiPronunciationMode implements StoredValue {
    ON_DEMAND("On demand"),
    ALWAYS_USE_AI("Always use AI");

    /** The value used when the stored label is unknown or absent. */
    public static final AiPronunciationMode DEFAULT = ON_DEMAND;

    private final String label;
    private final List<String> legacyLabels;

    AiPronunciationMode(String label, String... legacyLabels) {
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
