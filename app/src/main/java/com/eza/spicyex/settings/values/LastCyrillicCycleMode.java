package com.eza.spicyex.settings.values;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** The Cyrillic mode a cycle returns to (Settings.LAST_CYRILLIC_CYCLE_MODE). */
public enum LastCyrillicCycleMode implements StoredValue {
    RUSSIAN("Russian"),
    UKRAINIAN("Ukrainian");

    /** The value used when the stored label is unknown or absent. */
    public static final LastCyrillicCycleMode DEFAULT = RUSSIAN;

    private final String label;
    private final List<String> legacyLabels;

    LastCyrillicCycleMode(String label, String... legacyLabels) {
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
