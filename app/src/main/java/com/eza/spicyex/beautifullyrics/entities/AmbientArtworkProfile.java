package com.eza.spicyex.beautifullyrics.entities;

/**
 * Compact artwork variance profile computed during {@link AmbientArtworkTexture} preprocessing.
 * Pure Java (no Android classes) so unit tests run on the JVM. A profile is a few floats plus
 * one packed average color — no bitmap or large object remains alive after preprocessing.
 */
public final class AmbientArtworkProfile {
    /** Detail classes driving adaptive renderer parameters. */
    public enum Detail {
        DETAILED,
        LOW_VARIANCE,
        NEAR_UNIFORM
    }

    /** Bounded renderer parameters derived from a profile. All fields are clamped. */
    public static final class RendererParams {
        /** Synthetic luminance modulation, 0.04..0.20 (4-20%). */
        public final float modulation;
        /** Spatial warp amplitude in UV units, always below the safe texture inset. */
        public final float warp;
        /** Artwork-derived hue blend scale, 0..1 (scaled to a bounded blend in-shader). */
        public final float hue;
        /** Full drift cycle duration in seconds, 8..14. */
        public final float cycleSeconds;

        RendererParams(float modulation, float warp, float hue, float cycleSeconds) {
            this.modulation = modulation;
            this.warp = warp;
            this.hue = hue;
            this.cycleSeconds = cycleSeconds;
        }
    }

    // Classification thresholds on normalized (0..1) statistics.
    // NEAR_UNIFORM: visually flat, e.g. single color or tiny JPEG noise (std < ~5/255).
    static final float NEAR_UNIFORM_LUM_VAR = 0.0004f;
    static final float NEAR_UNIFORM_HUE_SPREAD = 0.03f;
    // LOW_VARIANCE: mostly single-color, e.g. std < ~16/255 or nearly achromatic / single hue.
    static final float LOW_VARIANCE_LUM_VAR = 0.004f;
    static final float LOW_VARIANCE_HUE_SPREAD = 0.10f;

    // Renderer tuning (mobile): detailed 4-8%, flat 10-18%, near-uniform up to 20%.
    static final float DETAILED_MODULATION = 0.06f;
    static final float LOW_VARIANCE_MODULATION = 0.14f;
    static final float NEAR_UNIFORM_MODULATION = 0.20f;

    // Warp stays below the 0.10 shader inset; broader for low-variance, minimal for uniform.
    static final float DETAILED_WARP = 0.035f;
    static final float LOW_VARIANCE_WARP = 0.050f;
    static final float NEAR_UNIFORM_WARP = 0.018f;

    static final float DETAILED_HUE = 0.80f;
    static final float LOW_VARIANCE_HUE = 1.00f;
    static final float NEAR_UNIFORM_HUE = 0.45f;

    static final float DETAILED_CYCLE_SECONDS = 9.0f;
    static final float LOW_VARIANCE_CYCLE_SECONDS = 11.0f;
    static final float NEAR_UNIFORM_CYCLE_SECONDS = 13.0f;

    /** Packed ARGB average color (alpha always 0xFF). */
    public final int averageColor;
    /** Normalized luminance variance, 0..~0.25 (0 for uniform). */
    public final float luminanceVariance;
    /** Circular hue spread, 0..1 (0 for uniform / achromatic). */
    public final float hueSpread;
    /** Average saturation 0..1, used to discount meaningless hues in grayscale art. */
    public final float averageSaturation;

    public AmbientArtworkProfile(int averageColor, float luminanceVariance,
            float hueSpread, float averageSaturation) {
        this.averageColor = averageColor;
        this.luminanceVariance = luminanceVariance;
        this.hueSpread = hueSpread;
        this.averageSaturation = averageSaturation;
    }

    /** Computes a deterministic profile from ARGB pixels. Does not modify the input. */
    public static AmbientArtworkProfile fromPixels(int[] pixels) {
        if (pixels == null || pixels.length == 0) {
            return new AmbientArtworkProfile(0xff000000, 0f, 0f, 0f);
        }
        int n = pixels.length;
        long sumR = 0, sumG = 0, sumB = 0;
        double sumLum = 0;
        double[] lums = null;
        // Two-pass keeps variance stable and exact for identical inputs; the array is transient
        // (one double per pixel) and never escapes — the profile itself stays a few floats.
        // For the 128x128 texture this is 16k doubles (~128 KiB) on the worker only.
        boolean useTwoPass = n <= 128 * 128 + 1;
        if (useTwoPass) lums = new double[n];
        for (int i = 0; i < n; i++) {
            int pixel = pixels[i];
            int r = (pixel >> 16) & 255, g = (pixel >> 8) & 255, b = pixel & 255;
            sumR += r; sumG += g; sumB += b;
            double lum = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0;
            if (lums != null) lums[i] = lum;
            sumLum += lum;
        }
        double meanLum = sumLum / n;
        double varLum;
        if (lums != null) {
            double acc = 0;
            for (double lum : lums) {
                double d = lum - meanLum;
                acc += d * d;
            }
            varLum = acc / n;
        } else {
            varLum = 0;
        }
        int avgR = (int) Math.round((double) sumR / n);
        int avgG = (int) Math.round((double) sumG / n);
        int avgB = (int) Math.round((double) sumB / n);
        int averageColor = 0xff000000 | (clamp255(avgR) << 16) | (clamp255(avgG) << 8) | clamp255(avgB);

        // Hue spread: circular variance of HSV hues, weighted to saturated pixels only so
        // grayscale art reports 0 spread instead of quantization noise.
        double sumSin = 0, sumCos = 0, sumSat = 0;
        int satCount = 0;
        for (int i = 0; i < n; i++) {
            int pixel = pixels[i];
            int r = (pixel >> 16) & 255, g = (pixel >> 8) & 255, b = pixel & 255;
            int max = Math.max(r, Math.max(g, b));
            int min = Math.min(r, Math.min(g, b));
            double sat = max == 0 ? 0 : (double) (max - min) / max;
            sumSat += sat;
            if (sat < 0.08) continue;
            double hue = rgbToHue01(r, g, b);
            double angle = hue * 2.0 * Math.PI;
            sumSin += Math.sin(angle);
            sumCos += Math.cos(angle);
            satCount++;
        }
        double avgSat = sumSat / n;
        double hueSpread;
        if (satCount == 0) {
            hueSpread = 0;
        } else {
            double meanSin = sumSin / satCount;
            double meanCos = sumCos / satCount;
            double resultant = Math.sqrt(meanSin * meanSin + meanCos * meanCos);
            hueSpread = clamp01(1.0 - resultant);
        }
        return new AmbientArtworkProfile(averageColor,
                (float) clamp01(varLum), (float) clamp01(hueSpread), (float) clamp01(avgSat));
    }

    /** Classifies detail from variance statistics. */
    public Detail detail() {
        return classify(luminanceVariance, hueSpread);
    }

    public static Detail classify(float luminanceVariance, float hueSpread) {
        if (luminanceVariance < NEAR_UNIFORM_LUM_VAR && hueSpread < NEAR_UNIFORM_HUE_SPREAD) {
            return Detail.NEAR_UNIFORM;
        }
        // Luminance drives the decision so high-contrast grayscale (zero hue spread) still
        // counts as detailed. Hue only pulls moderate-luminance single-hue art into flat.
        if (luminanceVariance < LOW_VARIANCE_LUM_VAR) {
            return Detail.LOW_VARIANCE;
        }
        if (hueSpread < LOW_VARIANCE_HUE_SPREAD && luminanceVariance < 0.02f) {
            return Detail.LOW_VARIANCE;
        }
        return Detail.DETAILED;
    }

    /** Whether the image is considered flat (low-variance or near-uniform). */
    public boolean isFlat() {
        return detail() != Detail.DETAILED;
    }

    /** Whether the image is nearly uniform (maximum safe modulation, minimal warp). */
    public boolean isNearUniform() {
        return detail() == Detail.NEAR_UNIFORM;
    }

    /** Selects bounded renderer parameters for this profile. */
    public RendererParams rendererParams() {
        return paramsFor(detail());
    }

    public static RendererParams paramsFor(Detail detail) {
        switch (detail) {
            case NEAR_UNIFORM:
                return new RendererParams(NEAR_UNIFORM_MODULATION, NEAR_UNIFORM_WARP,
                        NEAR_UNIFORM_HUE, NEAR_UNIFORM_CYCLE_SECONDS);
            case LOW_VARIANCE:
                return new RendererParams(LOW_VARIANCE_MODULATION, LOW_VARIANCE_WARP,
                        LOW_VARIANCE_HUE, LOW_VARIANCE_CYCLE_SECONDS);
            case DETAILED:
            default:
                return new RendererParams(DETAILED_MODULATION, DETAILED_WARP,
                        DETAILED_HUE, DETAILED_CYCLE_SECONDS);
        }
    }

    /** Normalized average color channels 0..1 for the AGSL avgColor uniform. */
    public float avgRed01() { return ((averageColor >> 16) & 255) / 255f; }
    public float avgGreen01() { return ((averageColor >> 8) & 255) / 255f; }
    public float avgBlue01() { return (averageColor & 255) / 255f; }

    private static double rgbToHue01(int r, int g, int b) {
        int max = Math.max(r, Math.max(g, b));
        int min = Math.min(r, Math.min(g, b));
        if (max == min) return 0;
        double delta = max - min;
        double hue;
        if (max == r) {
            hue = ((g - b) / delta) % 6.0;
        } else if (max == g) {
            hue = (b - r) / delta + 2.0;
        } else {
            hue = (r - g) / delta + 4.0;
        }
        hue /= 6.0;
        if (hue < 0) hue += 1.0;
        return hue;
    }

    private static int clamp255(int v) { return v < 0 ? 0 : Math.min(255, v); }
    private static double clamp01(double v) { return v < 0 ? 0 : Math.min(1.0, v); }
}
