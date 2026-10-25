package com.eza.spicyex.settings.values;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** The Japanese mode a cycle returns to (Settings.LAST_JAPANESE_CYCLE_MODE). */
public enum LastJapaneseCycleMode implements StoredValue {
    ROMAJI_ONLY("romaji_only"),
    FURIGANA_ONLY("furigana_only"),
    FURIGANA_ROMAJI("furigana_romaji");

    /** The value used when the stored label is unknown or absent. */
    public static final LastJapaneseCycleMode DEFAULT = ROMAJI_ONLY;

    private final String label;
    private final List<String> legacyLabels;

    LastJapaneseCycleMode(String label, String... legacyLabels) {
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
