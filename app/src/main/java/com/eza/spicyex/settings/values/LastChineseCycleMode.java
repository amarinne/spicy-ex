package com.eza.spicyex.settings.values;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** The Chinese mode a cycle returns to (Settings.LAST_CHINESE_CYCLE_MODE). */
public enum LastChineseCycleMode implements StoredValue {
    PINYIN("pinyin"),
    JYUTPING("jyutping");

    /** The value used when the stored label is unknown or absent. */
    public static final LastChineseCycleMode DEFAULT = PINYIN;

    private final String label;
    private final List<String> legacyLabels;

    LastChineseCycleMode(String label, String... legacyLabels) {
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
