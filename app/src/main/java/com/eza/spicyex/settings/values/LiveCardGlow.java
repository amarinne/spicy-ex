package com.eza.spicyex.settings.values;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** The now-playing card glow (Settings.LIVE_CARD_GLOW). */
public enum LiveCardGlow implements StoredValue {
    OFF("Off"),
    /** The retired "Subtle line" choice rendered like this one. */
    WORD_ONLY("Word only", "Subtle line");

    /** The value used when the stored label is unknown or absent. */
    public static final LiveCardGlow DEFAULT = OFF;

    private final String label;
    private final List<String> legacyLabels;

    LiveCardGlow(String label, String... legacyLabels) {
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
