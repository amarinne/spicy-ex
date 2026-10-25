package com.eza.spicyex.settings.values;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Which side the top chrome cluster sits on (Settings.CHROME_CLUSTER_POSITION). */
public enum ChromeClusterPosition implements StoredValue {
    LEFT("Left"),
    RIGHT("Right");

    /** The value used when the stored label is unknown or absent. */
    public static final ChromeClusterPosition DEFAULT = RIGHT;

    private final String label;
    private final List<String> legacyLabels;

    ChromeClusterPosition(String label, String... legacyLabels) {
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
