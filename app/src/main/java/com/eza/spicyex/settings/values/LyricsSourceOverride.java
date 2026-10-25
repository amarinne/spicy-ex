package com.eza.spicyex.settings.values;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** The strict lyric source override (Settings.LYRICS_SOURCE_OVERRIDE). */
public enum LyricsSourceOverride implements StoredValue {
    AUTO("Auto"),
    APPLE_MUSIC("Apple Music"),
    /** Retired label kept as a stored value for existing installs; it means Apple Music. */
    SPICY("Spicy"),
    SPOTIFY("Spotify"),
    LRCLIB("LRCLIB");

    /** The value used when the stored label is unknown or absent. */
    public static final LyricsSourceOverride DEFAULT = AUTO;

    private final String label;
    private final List<String> legacyLabels;

    LyricsSourceOverride(String label, String... legacyLabels) {
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
