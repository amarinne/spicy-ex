package com.eza.spicyex.settings.values;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** The bounce curve (Settings.WORD_BOUNCE_STYLE). */
public enum WordBounceStyle implements StoredValue {
    PHRASE_ZOOM("Phrase zoom"),
    WORD_ZOOM("Word zoom"),
    PHRASE_LIFT("Phrase lift"),
    WORD_LIFT("Word lift"),
    APPLE_LIFT("Apple lift");

    /** The value used when the stored label is unknown or absent. */
    public static final WordBounceStyle DEFAULT = PHRASE_ZOOM;

    private final String label;
    private final List<String> legacyLabels;

    WordBounceStyle(String label, String... legacyLabels) {
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

    /** The shared lift curves; the Apple lift is the Apple-owned motion, not a shared one. */
    public boolean isLift() {
        return this == PHRASE_LIFT || this == WORD_LIFT;
    }

    /** The curves that move a single word rather than the whole phrase. */
    public boolean isWordLevel() {
        return this == WORD_ZOOM || this == WORD_LIFT;
    }
}
