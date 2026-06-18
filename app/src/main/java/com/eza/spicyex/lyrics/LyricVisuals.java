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
            hsv[1] = Math.min(0.58f, hsv[1] * 1.15f + 0.08f);
            hsv[2] = Math.min(0.30f, hsv[2] * 1.32f + 0.035f);
        } else {
            hsv[1] = Math.min(0.82f, hsv[1] * 1.18f + 0.10f);
            hsv[2] = Math.min(0.58f, hsv[2] * 0.92f + 0.08f);
        }
        int high = Color.HSVToColor(hsv);
        return new int[]{high, min, forceDark ? Color.rgb(5, 5, 6) : Color.rgb(18, 18, 18)};
    }

    /** Port of Spicy 6 dynamicBackground.forceDarkColor() when force-dark background active. */
    public static int forceDarkColor(int color) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[1] = Math.min(hsv[1] * 0.62f, 0.50f);
        hsv[2] = Math.min(hsv[2] * 0.58f, 0.24f);
        return Color.HSVToColor(hsv);
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
