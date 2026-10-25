package com.eza.spicyex.settings.values;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** The lyric font family (Settings.LYRICS_FONT). */
public enum LyricsFont implements StoredValue {
    SPOTIFY("spotify"),
    APPLE("apple"),
    CUSTOM("custom");

    /** The value used when the stored label is unknown or absent. */
    public static final LyricsFont DEFAULT = SPOTIFY;

    private final String label;
    private final List<String> legacyLabels;

    LyricsFont(String label, String... legacyLabels) {
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
