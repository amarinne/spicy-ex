package com.eza.spicyex.settings.values;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** The picture-in-picture window shape (Settings.PIP_SHAPE). */
public enum PipShape implements StoredValue {
    PORTRAIT("Portrait"),
    SQUARE("Square"),
    TALL("Tall"),
    LANDSCAPE("Landscape"),
    WIDE("Wide");

    /** The value used when the stored label is unknown or absent. */
    public static final PipShape DEFAULT = PORTRAIT;

    private final String label;
    private final List<String> legacyLabels;

    PipShape(String label, String... legacyLabels) {
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

    /** The window's width and height. */
    public int[] ratio() {
        switch (this) {
            case SQUARE: return new int[]{1, 1};
            case TALL: return new int[]{9, 16};
            case LANDSCAPE: return new int[]{4, 3};
            case WIDE: return new int[]{16, 9};
            default: return new int[]{3, 4};
        }
    }
}
