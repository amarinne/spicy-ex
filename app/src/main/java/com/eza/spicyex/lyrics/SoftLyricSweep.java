package com.eza.spicyex.lyrics;

/** Hyperglow's 40-percent sweep band, with a bright unsung floor for car displays. */
public final class SoftLyricSweep {
    public static final float UNSUNG_ALPHA = 0.55f;
    public static final float MIDDLE_ALPHA = 0.80f;
    private SoftLyricSweep() { }
    public static float band(float extent) { return Math.max(1f, Math.max(0f, extent) * 0.4f); }
    public static float start(float extent, float progress) {
        float band = band(extent);
        return -band + (Math.max(0f, extent) + band) * Math.max(0f, Math.min(1f, progress));
    }
}
