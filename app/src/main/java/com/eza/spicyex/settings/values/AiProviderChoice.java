package com.eza.spicyex.settings.values;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** The provider selected for AI features (Settings.AI_PROVIDER). */
public enum AiProviderChoice implements StoredValue {
    GEMINI("gemini"),
    OPENAI("openai"),
    OPENROUTER("openrouter"),
    DEEPSEEK("deepseek"),
    CUSTOM("custom");

    /** The value used when the stored label is unknown or absent. */
    public static final AiProviderChoice DEFAULT = GEMINI;

    private final String label;
    private final List<String> legacyLabels;

    AiProviderChoice(String label, String... legacyLabels) {
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
