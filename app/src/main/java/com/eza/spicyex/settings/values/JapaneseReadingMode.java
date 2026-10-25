package com.eza.spicyex.settings.values;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** The Japanese reading shown under a line (Settings.JAPANESE_READING_MODE). */
public enum JapaneseReadingMode implements StoredValue {
    OFF("off"),
    FURIGANA_ONLY("furigana_only"),
    FURIGANA_ROMAJI("furigana_romaji"),
    ROMAJI_ONLY("romaji_only"),
    CYCLE("cycle");

    /** The value used when the stored label is unknown or absent. */
    public static final JapaneseReadingMode DEFAULT = ROMAJI_ONLY;

    private final String label;
    private final List<String> legacyLabels;

    JapaneseReadingMode(String label, String... legacyLabels) {
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
