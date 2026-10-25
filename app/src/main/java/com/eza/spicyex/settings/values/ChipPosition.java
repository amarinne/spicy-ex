package com.eza.spicyex.settings.values;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Which side a floating chip sits on (Settings.SKIP_CHIP_POSITION, Settings.FOLLOW_CHIP_POSITION). */
public enum ChipPosition implements StoredValue {
    LEFT("Left"),
    CENTER("Center"),
    RIGHT("Right");

    /** The value used when the stored label is unknown or absent. */
    public static final ChipPosition DEFAULT = RIGHT;

    private final String label;
    private final List<String> legacyLabels;

    ChipPosition(String label, String... legacyLabels) {
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
