package com.eza.spicyex.settings.values;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** The add-to-Liked-Songs button in the top chrome (Settings.LIKED_SONGS_BUTTON). */
public enum LikedSongsButton implements StoredValue {
    OFF("Off"),
    HEART("Heart"),
    STAR("Star");

    /** The value used when the stored label is unknown or absent. */
    public static final LikedSongsButton DEFAULT = OFF;

    private final String label;
    private final List<String> legacyLabels;

    LikedSongsButton(String label, String... legacyLabels) {
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
