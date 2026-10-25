package com.eza.spicyex.settings.values;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** The interlude indicator glyph (Settings.INTERLUDE_ICON). */
public enum InterludeIcon implements StoredValue {
    DOTS("dots"),
    NOTE("note");

    /** The value used when the stored label is unknown or absent. */
    public static final InterludeIcon DEFAULT = NOTE;

    private final String label;
    private final List<String> legacyLabels;

    InterludeIcon(String label, String... legacyLabels) {
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
