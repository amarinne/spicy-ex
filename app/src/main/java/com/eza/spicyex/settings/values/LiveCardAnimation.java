package com.eza.spicyex.settings.values;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** The now-playing card animation (Settings.LIVE_CARD_ANIMATION). */
public enum LiveCardAnimation implements StoredValue {
    MINIMAL("Minimal"),
    KARAOKE_FILL("Karaoke fill"),
    SPOTLIGHT_WORD("Spotlight word", "Full");

    /** The value used when the stored label is unknown or absent. */
    public static final LiveCardAnimation DEFAULT = KARAOKE_FILL;

    private final String label;
    private final List<String> legacyLabels;

    LiveCardAnimation(String label, String... legacyLabels) {
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
