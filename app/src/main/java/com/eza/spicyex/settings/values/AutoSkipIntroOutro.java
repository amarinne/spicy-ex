package com.eza.spicyex.settings.values;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** How the lyrics screen skips an intro or outro gap (Settings.AUTO_SKIP_INTRO_OUTRO). */
public enum AutoSkipIntroOutro implements StoredValue {
    OFF("Off"),
    ON_DEMAND("On demand"),
    AUTO("Auto");

    /** The value used when the stored label is unknown or absent. */
    public static final AutoSkipIntroOutro DEFAULT = OFF;

    private final String label;
    private final List<String> legacyLabels;

    AutoSkipIntroOutro(String label, String... legacyLabels) {
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
