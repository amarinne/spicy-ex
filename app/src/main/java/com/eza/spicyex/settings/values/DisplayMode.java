package com.eza.spicyex.settings.values;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Which lyric rows a document displays (Settings.DISPLAY_MODE). */
public enum DisplayMode implements StoredValue {
    ORIGINAL("original"),
    ROMANIZED("romanized"),
    ORIGINAL_ROMANIZED("original_romanized"),
    ORIGINAL_TRANSLATION("original_translation"),
    ORIGINAL_ROMANIZED_TRANSLATION("original_romanized_translation");

    /** The value used when the stored label is unknown or absent. */
    public static final DisplayMode DEFAULT = ORIGINAL_ROMANIZED;

    private final String label;
    private final List<String> legacyLabels;

    DisplayMode(String label, String... legacyLabels) {
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
