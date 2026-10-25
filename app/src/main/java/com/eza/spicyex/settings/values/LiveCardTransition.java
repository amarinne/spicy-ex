package com.eza.spicyex.settings.values;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** The now-playing card transition (Settings.LIVE_CARD_TRANSITION). */
public enum LiveCardTransition implements StoredValue {
    FADE_UP("Fade up"),
    CROSSFADE("Crossfade"),
    NONE("None");

    /** The value used when the stored label is unknown or absent. */
    public static final LiveCardTransition DEFAULT = FADE_UP;

    private final String label;
    private final List<String> legacyLabels;

    LiveCardTransition(String label, String... legacyLabels) {
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
