package com.eza.spicyex.settings.values;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** What a tap on the now-playing card opens (Settings.LIVE_CARD_TAP_TARGET). */
public enum LiveCardTapTarget implements StoredValue {
    FULLSCREEN("Fullscreen"),
    ARTWORK("Artwork");

    /** The value used when the stored label is unknown or absent. */
    public static final LiveCardTapTarget DEFAULT = FULLSCREEN;

    private final String label;
    private final List<String> legacyLabels;

    LiveCardTapTarget(String label, String... legacyLabels) {
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
