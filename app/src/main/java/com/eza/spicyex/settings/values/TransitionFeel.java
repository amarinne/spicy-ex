package com.eza.spicyex.settings.values;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** The pacing of chrome transitions (Settings.TRANSITION_FEEL). */
public enum TransitionFeel implements StoredValue {
    INSTANT("Instant"),
    FAST("Fast"),
    RELAXED("Relaxed");

    /** The value used when the stored label is unknown or absent. */
    public static final TransitionFeel DEFAULT = FAST;

    private final String label;
    private final List<String> legacyLabels;

    TransitionFeel(String label, String... legacyLabels) {
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
