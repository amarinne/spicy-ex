package com.eza.spicyex.ambient;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Pure-Java artwork colour extraction and the missing-artwork fallback palette.
 *
 * <p>Extraction keeps the artwork's own dominant hues: chromatic pixels are bucketed by hue, and
 * neutral pixels keep their light/dark structure instead of being discarded. Colours are emitted
 * as the weighted average of their source pixels, with no hue, saturation or brightness forcing,
 * so the renderer's own grade is the only colour transform between the cover and the screen.
 *
 * <p>Deliberately free of Android types so the JVM tests can exercise the same maths the
 * controller runs on a worker thread.
 */
public final class AmbientArtworkPalette {
    /** Cover fields handed to the page mesh and the Paper palette binding. */
    public static final int FIELD_COUNT = 4;

    private static final int HUE_BINS = 16;
    private static final int NEUTRAL_BANDS = 4;
    /** Below this saturation a pixel has no meaningful hue; it joins a neutral band. */
    private static final float NEUTRAL_SATURATION = 0.15f;
    /** Neutral area counts slightly less than a chromatic one so a cover's hues survive. */
    private static final float NEUTRAL_SCORE_PER_PIXEL = 0.5f;
    private static final float NEUTRAL_SHARE_FOR_ONE = 0.10f;
    private static final float NEUTRAL_SHARE_FOR_TWO = 0.35f;
    /** 16 bins keep colours at least two bins (45 degrees) apart. */
    private static final int MIN_HUE_BIN_DISTANCE = 2;
    private static final float NEUTRAL_SEED_SATURATION = 0.06f;

    private AmbientArtworkPalette() {
    }

    /**
     * The artwork's dominant colours, most prominent first, plus neutral fields when the cover has
     * meaningful light/dark structure. Null only when nothing opaque was supplied.
     */
    public static int[] extract(int[] pixels, int max) {
        if (pixels == null || pixels.length == 0 || max <= 0) return null;

        float[] hueWeight = new float[HUE_BINS];
        float[] hueRed = new float[HUE_BINS];
        float[] hueGreen = new float[HUE_BINS];
        float[] hueBlue = new float[HUE_BINS];
        int[] neutralCount = new int[NEUTRAL_BANDS];
        long[] neutralRed = new long[NEUTRAL_BANDS];
        long[] neutralGreen = new long[NEUTRAL_BANDS];
        long[] neutralBlue = new long[NEUTRAL_BANDS];

        int opaque = 0;
        int neutralPixels = 0;
        float[] hsv = new float[3];
        for (int pixel : pixels) {
            int alpha = (pixel >>> 24) & 0xff;
            if (alpha < 8) continue;
            opaque++;
            int red = (pixel >> 16) & 0xff;
            int green = (pixel >> 8) & 0xff;
            int blue = pixel & 0xff;
            rgbToHsv(red, green, blue, hsv);
            if (hsv[1] < NEUTRAL_SATURATION) {
                int band = Math.min(NEUTRAL_BANDS - 1, (int) (hsv[2] * NEUTRAL_BANDS));
                neutralCount[band]++;
                neutralRed[band] += red;
                neutralGreen[band] += green;
                neutralBlue[band] += blue;
                neutralPixels++;
                continue;
            }
            int bin = Math.min(HUE_BINS - 1, (int) (hsv[0] / 360f * HUE_BINS));
            // Area is the primary signal; saturation and brightness only break near-ties so a
            // small accent cannot replace the cover's dominant field.
            float weight = (0.35f + hsv[1]) * (0.35f + hsv[2]);
            hueWeight[bin] += weight;
            hueRed[bin] += red * weight;
            hueGreen[bin] += green * weight;
            hueBlue[bin] += blue * weight;
        }
        if (opaque == 0) return null;

        List<Candidate> hueCandidates = new ArrayList<>();
        for (int bin = 0; bin < HUE_BINS; bin++) {
            if (hueWeight[bin] <= 0f) continue;
            int color = color(
                    Math.round(hueRed[bin] / hueWeight[bin]),
                    Math.round(hueGreen[bin] / hueWeight[bin]),
                    Math.round(hueBlue[bin] / hueWeight[bin]));
            hueCandidates.add(new Candidate(bin, color, hueWeight[bin]));
        }
        Collections.sort(hueCandidates, (a, b) -> {
            int byScore = Float.compare(b.score, a.score);
            return byScore != 0 ? byScore : Integer.compare(a.hueBin, b.hueBin);
        });

        List<Candidate> neutralCandidates = new ArrayList<>();
        for (int band = 0; band < NEUTRAL_BANDS; band++) {
            int count = neutralCount[band];
            if (count == 0) continue;
            int color = color(
                    Math.round(neutralRed[band] / (float) count),
                    Math.round(neutralGreen[band] / (float) count),
                    Math.round(neutralBlue[band] / (float) count));
            neutralCandidates.add(new Candidate(-1, color, count * NEUTRAL_SCORE_PER_PIXEL));
        }
        Collections.sort(neutralCandidates, (a, b) -> {
            int byScore = Float.compare(b.score, a.score);
            return byScore != 0 ? byScore : Integer.compare(a.hueBin, b.hueBin);
        });

        int neutralCap;
        if (hueCandidates.isEmpty()) {
            neutralCap = max;
        } else if (neutralPixels >= opaque * NEUTRAL_SHARE_FOR_TWO) {
            neutralCap = Math.min(2, max);
        } else if (neutralPixels >= opaque * NEUTRAL_SHARE_FOR_ONE) {
            neutralCap = 1;
        } else {
            neutralCap = 0;
        }

        int[] picked = new int[max];
        boolean[] pickedHues = new boolean[HUE_BINS];
        int pickedCount = 0;
        int hueIndex = 0;
        int neutralIndex = 0;
        int neutralPicked = 0;
        while (pickedCount < max) {
            float hueScore = hueIndex < hueCandidates.size()
                    ? hueCandidates.get(hueIndex).score : Float.NEGATIVE_INFINITY;
            float neutralScore = neutralIndex < neutralCandidates.size()
                    && neutralPicked < neutralCap
                    ? neutralCandidates.get(neutralIndex).score : Float.NEGATIVE_INFINITY;
            if (hueScore == Float.NEGATIVE_INFINITY
                    && neutralScore == Float.NEGATIVE_INFINITY) break;
            if (hueScore >= neutralScore) {
                Candidate candidate = hueCandidates.get(hueIndex++);
                if (!hasNearbyHue(candidate.hueBin, pickedHues)) {
                    picked[pickedCount++] = candidate.color;
                    pickedHues[candidate.hueBin] = true;
                }
            } else {
                picked[pickedCount++] = neutralCandidates.get(neutralIndex++).color;
                neutralPicked++;
            }
        }
        if (pickedCount == 0) return null;
        int[] result = Arrays.copyOf(picked, pickedCount);
        if (result.length == 1 && max >= 2) {
            // The ambient palette handoff needs two fields; a uniform cover stays one flat colour.
            return new int[]{result[0], result[0]};
        }
        return result;
    }

    /**
     * The missing-artwork gradient: the cover colour's own hue over a deep base. A neutral seed
     * stays neutral; only its light/dark contrast is shaped.
     */
    public static int[] fallback(int seed, boolean forceDark) {
        boolean neutralSeed = toHsv(seed)[1] < NEUTRAL_SEED_SATURATION;
        int min = forceDark ? forceDark(seed) : seed;
        float[] hsv = toHsv(min);
        if (forceDark) {
            if (!neutralSeed) hsv[1] = Math.min(0.62f, hsv[1] * 1.22f + 0.09f);
            hsv[2] = Math.min(0.38f, hsv[2] * 1.45f + 0.055f);
        } else {
            if (!neutralSeed) hsv[1] = Math.min(0.82f, hsv[1] * 1.18f + 0.10f);
            hsv[2] = Math.min(0.58f, hsv[2] * 0.92f + 0.08f);
        }
        int high = toColor(hsv);
        if (forceDark) high = separateByValue(high, min);
        return new int[]{high, min, forceDark ? 0xff050506 : 0xff121212};
    }

    /** The page mesh's deep base: a shade of the lead field, not near-black. */
    public static int baseColor(int field) {
        float[] hsv = toHsv(field);
        hsv[1] = Math.min(0.85f, hsv[1] * 0.9f);
        hsv[2] = 0.24f;
        return toColor(hsv);
    }

    /** Curve-based force-dark: bright colours compress hard; already-dark colours keep their shape. */
    public static int forceDark(int color) {
        float[] hsv = toHsv(color);
        float originalValue = hsv[2];
        float saturationFloor = hsv[1] < 0.02f ? 0f : 0.055f;
        hsv[1] = Math.min(0.58f, hsv[1] * (originalValue > 0.62f ? 0.76f : 0.94f)
                + saturationFloor);
        hsv[2] = darkValueCurve(originalValue);
        return toColor(hsv);
    }

    private static int separateByValue(int color, int reference) {
        float[] target = toHsv(color);
        float[] base = toHsv(reference);
        float hueDelta = Math.abs(target[0] - base[0]);
        hueDelta = Math.min(hueDelta, 360f - hueDelta);
        if (hueDelta < 18f && Math.abs(target[2] - base[2]) < 0.11f) {
            // Raise only the value: a hue shift here would make the fallback disagree with the
            // cover even when Spotify's extracted colour was accurate.
            target[2] = Math.min(0.40f, Math.max(target[2], base[2] + 0.11f));
        }
        return toColor(target);
    }

    private static float darkValueCurve(float value) {
        float v = clamp01(value);
        if (v < 0.22f) return Math.min(0.24f, Math.max(0.055f, v * 0.96f + 0.018f));
        float compressed = 0.07f + 0.34f * (1f - (float) Math.exp(-2.55f * v));
        return Math.min(v, Math.min(0.42f, compressed));
    }

    private static boolean hasNearbyHue(int bin, boolean[] pickedHues) {
        for (int selected = 0; selected < HUE_BINS; selected++) {
            if (!pickedHues[selected]) continue;
            int distance = Math.abs(selected - bin);
            if (Math.min(distance, HUE_BINS - distance) < MIN_HUE_BIN_DISTANCE) return true;
        }
        return false;
    }

    private static int color(int red, int green, int blue) {
        return 0xff000000 | (clamp255(red) << 16) | (clamp255(green) << 8) | clamp255(blue);
    }

    static float[] toHsv(int color) {
        float[] hsv = new float[3];
        rgbToHsv((color >> 16) & 0xff, (color >> 8) & 0xff, color & 0xff, hsv);
        return hsv;
    }

    static void rgbToHsv(int red, int green, int blue, float[] out) {
        float r = red / 255f;
        float g = green / 255f;
        float b = blue / 255f;
        float max = Math.max(r, Math.max(g, b));
        float min = Math.min(r, Math.min(g, b));
        float delta = max - min;
        float hue;
        if (delta == 0f) {
            hue = 0f;
        } else if (max == r) {
            hue = 60f * (((g - b) / delta) % 6f);
        } else if (max == g) {
            hue = 60f * (((b - r) / delta) + 2f);
        } else {
            hue = 60f * (((r - g) / delta) + 4f);
        }
        if (hue < 0f) hue += 360f;
        out[0] = hue;
        out[1] = max == 0f ? 0f : delta / max;
        out[2] = max;
    }

    static int toColor(float[] hsv) {
        return toColor(hsv[0], hsv[1], hsv[2]);
    }

    static int toColor(float hue, float saturation, float value) {
        float h = ((hue % 360f) + 360f) % 360f;
        float s = clamp01(saturation);
        float v = clamp01(value);
        float chroma = v * s;
        float x = chroma * (1f - Math.abs(((h / 60f) % 2f) - 1f));
        float m = v - chroma;
        float r;
        float g;
        float b;
        if (h < 60f) {
            r = chroma; g = x; b = 0f;
        } else if (h < 120f) {
            r = x; g = chroma; b = 0f;
        } else if (h < 180f) {
            r = 0f; g = chroma; b = x;
        } else if (h < 240f) {
            r = 0f; g = x; b = chroma;
        } else if (h < 300f) {
            r = x; g = 0f; b = chroma;
        } else {
            r = chroma; g = 0f; b = x;
        }
        return color(Math.round((r + m) * 255f), Math.round((g + m) * 255f),
                Math.round((b + m) * 255f));
    }

    private static int clamp255(int value) {
        return value < 0 ? 0 : Math.min(255, value);
    }

    private static float clamp01(float value) {
        return value < 0f ? 0f : Math.min(1f, value);
    }

    private static final class Candidate {
        final int hueBin;
        final int color;
        final float score;

        Candidate(int hueBin, int color, float score) {
            this.hueBin = hueBin;
            this.color = color;
            this.score = score;
        }
    }
}
