package com.eza.spicyex.settings.values;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** The now-playing card overflow (Settings.LIVE_CARD_OVERFLOW). */
public enum LiveCardOverflow implements StoredValue {
    WRAP("Wrap"),
    SCROLL_WITH_LYRIC("Scroll with lyric"),
    CLIP("Clip");

    /** The value used when the stored label is unknown or absent. */
    public static final LiveCardOverflow DEFAULT = WRAP;

    private final String label;
    private final List<String> legacyLabels;

    LiveCardOverflow(String label, String... legacyLabels) {
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
