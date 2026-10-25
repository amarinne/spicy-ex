package com.eza.spicyex.settings.values;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** The karaoke fill direction (Settings.LINE_SYNC_FILL, Settings.LIVE_CARD_LINE_SYNC_FILL). */
public enum LineSyncFill implements StoredValue {
    TOP_TO_BOTTOM("Top to bottom"),
    BLOCK("Left to right (block)"),
    SENTENCE("Left to right (sentence)"),
    /** The word-by-word fill the spotlight card projects; the preference never holds it. */
    WORD("Left to right (word)") {
        @Override
        public boolean isStored() {
            return false;
        }
    };

    /** The value used when the stored label is unknown or absent. */
    public static final LineSyncFill DEFAULT = TOP_TO_BOTTOM;

    private final String label;
    private final List<String> legacyLabels;

    LineSyncFill(String label, String... legacyLabels) {
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
