package com.eza.spicyex.settings.values;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Which pipeline supplies AI pronunciation (Settings.AI_PRONUNCIATION_SOURCE). */
public enum AiPronunciationSource implements StoredValue {
    LAYERED("Layered"),
    AI_ONLY("AI only");

    /** The value used when the stored label is unknown or absent. */
    public static final AiPronunciationSource DEFAULT = LAYERED;

    private final String label;
    private final List<String> legacyLabels;

    AiPronunciationSource(String label, String... legacyLabels) {
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
