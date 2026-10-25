package com.eza.spicyex.settings.values;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Whether the top chrome cluster is a rail or a row (Settings.CHROME_CLUSTER_LAYOUT). */
public enum ChromeClusterLayout implements StoredValue {
    AUTO("Auto"),
    VERTICAL("Vertical"),
    HORIZONTAL("Horizontal");

    /** The value used when the stored label is unknown or absent. */
    public static final ChromeClusterLayout DEFAULT = AUTO;

    private final String label;
    private final List<String> legacyLabels;

    ChromeClusterLayout(String label, String... legacyLabels) {
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
