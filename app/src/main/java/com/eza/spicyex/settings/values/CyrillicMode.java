package com.eza.spicyex.settings.values;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** The Cyrillic reading shown under a line (Settings.CYRILLIC_MODE). */
public enum CyrillicMode implements StoredValue {
    OFF("Off"),
    RUSSIAN("Russian"),
    UKRAINIAN("Ukrainian"),
    CYCLE("cycle");

    /** The value used when the stored label is unknown or absent. */
    public static final CyrillicMode DEFAULT = RUSSIAN;

    private final String label;
    private final List<String> legacyLabels;

    CyrillicMode(String label, String... legacyLabels) {
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
