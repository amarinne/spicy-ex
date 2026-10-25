package com.eza.spicyex.settings.values;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** What the translation button does on tap (Settings.AI_BUTTON_BEHAVIOR). */
public enum AiButtonBehavior implements StoredValue {
    GENERATE_THEN_TOGGLE("Generate AI output, then toggle"),
    TOGGLE_DISPLAY_ONLY("Toggle display only");

    /** The value used when the stored label is unknown or absent. */
    public static final AiButtonBehavior DEFAULT = GENERATE_THEN_TOGGLE;

    private final String label;
    private final List<String> legacyLabels;

    AiButtonBehavior(String label, String... legacyLabels) {
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
