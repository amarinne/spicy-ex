package com.eza.spicyex.settings;

/**
 * What {@link com.eza.spicyex.Settings#LYRICS_BLUR_STRENGTH} stores.
 *
 * <p>0 is off. 1-250 is the standard distance curve at that percent (100 is the curve's own
 * strength). 251-500 is the legacy "Slight" distance curve at (value - 250) percent.
 *
 * <p>The legacy selector chose a distance curve, not a magnitude: the two curves have different
 * shapes, so no single percentage renders both. The stored value therefore carries the curve
 * as well as the percent, and every pair a v2.0.41 install could hold migrates to a value that
 * renders exactly as it did.
 */
public final class BlurStrength {
    /** The first percent of the legacy "Slight" band; the standard curve owns 1-250. */
    public static final int LEGACY_BAND = 250;

    private BlurStrength() {
    }

    /** The percent the stored value renders at; 0 is off. */
    public static int percent(int stored) {
        return stored > LEGACY_BAND ? stored - LEGACY_BAND : stored;
    }

    /** True when the stored value renders with the standard distance curve. */
    public static boolean standardCurve(int stored) {
        return stored > 0 && stored <= LEGACY_BAND;
    }

    /** The stored value for a percent on the legacy "Slight" distance curve. */
    public static int legacySlight(int percent) {
        return LEGACY_BAND + percent;
    }

    /**
     * The stored value for a v2.0.41 pair: the legacy selector and its intensity percentage.
     * "Off" is off, "Heavy" keeps the standard curve, "Slight" keeps the legacy curve.
     */
    public static int fromLegacyPair(String level, int intensity) {
        if ("Slight".equals(level)) return legacySlight(intensity);
        if ("Heavy".equals(level)) return intensity;
        return 0;
    }
}
