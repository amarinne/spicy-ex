package com.eza.spicyex.settings.values;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** The glyph the follow-lyrics chip shows (Settings.FOLLOW_CHIP_ICON). */
public enum FollowChipIcon implements StoredValue {
    ADAPTIVE_ARROW("Adaptive arrow"),
    STATIC_ARROW("Static arrow"),
    WAVEFORM("Waveform");

    /** The value used when the stored label is unknown or absent. */
    public static final FollowChipIcon DEFAULT = ADAPTIVE_ARROW;

    private final String label;
    private final List<String> legacyLabels;

    FollowChipIcon(String label, String... legacyLabels) {
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
