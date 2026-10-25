package com.eza.spicyex.settings.values;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The extra line under the lyric (Settings.LIVE_CARD_SECONDARY_MODE,
 * Settings.AUTO_SECONDARY_TEXT).
 */
public enum SecondaryTextMode implements StoredValue {
    MAIN_ONLY("Main only"),
    TRANSLITERATION("Transliteration", "Romanization", "Romanized"),
    TRANSLATION("Translation"),
    BOTH("Both");

    /** The value used when the stored label is unknown or absent. */
    public static final SecondaryTextMode DEFAULT = MAIN_ONLY;

    private final String label;
    private final List<String> legacyLabels;

    SecondaryTextMode(String label, String... legacyLabels) {
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

    /** The transliteration row shows under this mode. */
    public boolean showsTransliteration() {
        return this == TRANSLITERATION || this == BOTH;
    }

    /** The translation row shows under this mode. */
    public boolean showsTranslation() {
        return this == TRANSLATION || this == BOTH;
    }
}
