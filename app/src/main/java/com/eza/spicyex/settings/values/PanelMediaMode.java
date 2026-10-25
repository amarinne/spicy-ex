package com.eza.spicyex.settings.values;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** The media controls on the artwork (Settings.PANEL_MEDIA_CONTROLS). */
public enum PanelMediaMode implements StoredValue {
    OFF("Off"),
    SINGLE_TAP("Single tap"),
    DOUBLE_TAP("Double tap");

    /** The value used when the stored label is unknown or absent. */
    public static final PanelMediaMode DEFAULT = SINGLE_TAP;

    private final String label;
    private final List<String> legacyLabels;

    PanelMediaMode(String label, String... legacyLabels) {
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

    /** Swipe plus tap gestures are enabled in every mode except Off. */
    public boolean gesturesEnabled() {
        return this != OFF;
    }

    /** A single tap opens the persistent play/pause overlay (Single-tap mode only). */
    public boolean revealsOnSingleTap() {
        return this == SINGLE_TAP;
    }
}
