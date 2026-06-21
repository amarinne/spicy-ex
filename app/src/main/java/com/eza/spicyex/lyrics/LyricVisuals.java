package com.eza.spicyex.lyrics;

import android.graphics.Color;

import java.util.ArrayList;
import java.util.List;
import static com.eza.spicyex.lyrics.LyricUtils.isBlank;
import static com.eza.spicyex.lyrics.LyricUtils.safe;

/** Display-only helpers for native Spicy lyric rows and backgrounds. */
public final class LyricVisuals {
    private LyricVisuals() {
    }

    public static int parseSpotifyExtractedColor(String raw) {
        String value = safe(raw).trim();
        if (value.isEmpty()) return Color.rgb(18, 18, 18);
        try {
            if (!value.startsWith("#")) value = "#" + value;
            return Color.parseColor(value);
        } catch (Throwable ignored) {
            return Color.rgb(18, 18, 18);
        }
    }

    public static int[] spicyColorBackgroundColors(int seed) {
        return spicyColorBackgroundColors(seed, true);
    }

    public static int[] spicyColorBackgroundColors(int seed, boolean forceDark) {
        int min = forceDark ? forceDarkColor(seed) : seed;
        float[] hsv = new float[3];
        Color.colorToHSV(min, hsv);
        if (forceDark) {
            hsv[1] = Math.min(0.62f, hsv[1] * 1.22f + 0.09f);
            hsv[2] = Math.min(0.38f, hsv[2] * 1.45f + 0.055f);
        } else {
            hsv[1] = Math.min(0.82f, hsv[1] * 1.18f + 0.10f);
            hsv[2] = Math.min(0.58f, hsv[2] * 0.92f + 0.08f);
        }
        int high = Color.HSVToColor(hsv);
        if (forceDark) high = ensureColorSeparation(high, min, 28f, 0.11f);
        return new int[]{high, min, forceDark ? Color.rgb(5, 5, 6) : Color.rgb(18, 18, 18)};
    }

    /** Curve-based force-dark: bright colors compress hard; already-dark colors keep their shape. */
    public static int forceDarkColor(int color) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        float originalV = hsv[2];
        hsv[1] = Math.min(0.58f, hsv[1] * (originalV > 0.62f ? 0.76f : 0.94f) + 0.055f);
        hsv[2] = darkValueCurve(originalV);
        return Color.HSVToColor(hsv);
    }

    private static float darkValueCurve(float value) {
        float v = clamp01(value);
        if (v < 0.22f) return Math.min(0.24f, Math.max(0.055f, v * 0.96f + 0.018f));
        float compressed = 0.07f + 0.34f * (1f - (float) Math.exp(-2.55f * v));
        return Math.min(v, Math.min(0.42f, compressed));
    }

    private static int ensureColorSeparation(int color, int reference, float hueShift, float minValueDelta) {
        float[] c = new float[3];
        float[] r = new float[3];
        Color.colorToHSV(color, c);
        Color.colorToHSV(reference, r);
        float hueDelta = Math.abs(c[0] - r[0]);
        hueDelta = Math.min(hueDelta, 360f - hueDelta);
        if (hueDelta < 18f && Math.abs(c[2] - r[2]) < minValueDelta) {
            c[0] = (c[0] + hueShift) % 360f;
            c[1] = Math.min(0.64f, c[1] + 0.08f);
            c[2] = Math.min(0.40f, Math.max(c[2], r[2] + minValueDelta));
        }
        return Color.HSVToColor(c);
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    public static int lyricTextSizeSp(String text) {
        String safeText = safe(text);
        int length = safeText.codePointCount(0, safeText.length());
        if (length >= 30) return 23;
        if (length >= 22) return 24;
        if (length >= 14) return 26;
        return 28;
    }

    public static int secondaryTextSizeSp(int baseTextSp) {
        return Math.max(14, Math.round(baseTextSp * 0.48f));
    }

    public static boolean shouldUseLetterAnimator(SyllableSegment seg) {
        if (seg == null || isBlank(seg.text)) return false;
        String text = safe(seg.text);
        int codePoints = text.codePointCount(0, text.length());
        return seg.totalMs >= 1000 && codePoints > 0 && codePoints <= 12;
    }

    public static List<String> splitCodePoints(String text) {
        ArrayList<String> out = new ArrayList<>();
        String value = safe(text);
        if (value.isEmpty()) return out;
        for (int i = 0; i < value.length();) {
            int codePoint = value.codePointAt(i);
            out.add(new String(Character.toChars(codePoint)));
            i += Character.charCount(codePoint);
        }
        return out;
    }

}
