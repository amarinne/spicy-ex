package com.eza.spicyex.settings.values;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** The now-playing card scroll scope (Settings.LIVE_CARD_SCROLL_SCOPE). */
public enum LiveCardScrollScope implements StoredValue {
    GROUPED("Grouped"),
    INDIVIDUAL_LINES("Individual lines");

    /** The value used when the stored label is unknown or absent. */
    public static final LiveCardScrollScope DEFAULT = GROUPED;

    private final String label;
    private final List<String> legacyLabels;

    LiveCardScrollScope(String label, String... legacyLabels) {
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
