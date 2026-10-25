package com.eza.spicyex.settings.values;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** The Chinese reading shown under a line (Settings.CHINESE_MODE). */
public enum ChineseMode implements StoredValue {
    OFF("off"),
    PINYIN("pinyin"),
    JYUTPING("jyutping"),
    PINYIN_EZA("pinyin-eza"),
    JYUTPING_EZA("jyutping-eza"),
    CYCLE("cycle");

    /** The value used when the stored label is unknown or absent. */
    public static final ChineseMode DEFAULT = PINYIN;

    private final String label;
    private final List<String> legacyLabels;

    ChineseMode(String label, String... legacyLabels) {
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
