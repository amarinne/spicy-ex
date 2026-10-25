package com.eza.spicyex.settings.values;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** The word bounce gate and its scope (Settings.WORD_BOUNCE). */
public enum WordBounceMode implements StoredValue {
    OFF("Off"),
    SYNCED_WORDS_ONLY("Word/syllable synced only"),
    ALL_SYNCED_ROWS("All synced rows");

    /** The value used when the stored label is unknown or absent. */
    public static final WordBounceMode DEFAULT = SYNCED_WORDS_ONLY;

    private final String label;
    private final List<String> legacyLabels;

    WordBounceMode(String label, String... legacyLabels) {
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
