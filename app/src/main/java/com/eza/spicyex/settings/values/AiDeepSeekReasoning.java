package com.eza.spicyex.settings.values;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** How much reasoning DeepSeek is asked to buy (Settings.AI_DEEPSEEK_REASONING). */
public enum AiDeepSeekReasoning implements StoredValue {
    OFF("Off"),
    LOW("Low"),
    HIGH("High"),
    MAX("Max");

    /** The value used when the stored label is unknown or absent. */
    public static final AiDeepSeekReasoning DEFAULT = LOW;

    private final String label;
    private final List<String> legacyLabels;

    AiDeepSeekReasoning(String label, String... legacyLabels) {
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
