package com.eza.spicyex.lyrics;

import com.eza.spicyex.ambient.AmbientArtworkPalette;
import com.eza.spicyex.lyrics.language.SpicyTextDetection;

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
        return AmbientArtworkPalette.fallback(seed, forceDark);
    }

    /** Curve-based force-dark: bright colors compress hard; already-dark colors keep their shape. */
    public static int forceDarkColor(int color) {
        return AmbientArtworkPalette.forceDark(color);
    }

    public static int lyricTextSizeSp(String text) {
        return lyricTextSizeSp(text, true);
    }

    /**
     * Base lyric size. Adaptive mode shrinks long lines so they fit; with adaptive off every
     * line uses the short-line size and long lines wrap instead.
     */
    public static int lyricTextSizeSp(String text, boolean adaptive) {
        if (!adaptive) return 28;
        String safeText = safe(text);
        int length = safeText.codePointCount(0, safeText.length());
        if (length >= 30) return 23;
        if (length >= 22) return 24;
        if (length >= 14) return 26;
        return 28;
    }

    /** Apple compact curve: same steps as the shared curve, shifted down ~4-5sp. */
    public static int appleLyricTextSizeSp(String text) {
        String safeText = safe(text);
        int length = safeText.codePointCount(0, safeText.length());
        if (length >= 30) return 19;
        if (length >= 22) return 20;
        if (length >= 14) return 21;
        return 23;
    }

    public static int secondaryTextSizeSp(int baseTextSp) {
        return Math.max(14, Math.round(baseTextSp * 0.48f));
    }

    public static boolean shouldUseLetterAnimator(SyllableSegment seg) {
        return shouldUseLetterAnimator(seg, false);
    }

    /**
     * Apple variant: the lift wave drives per-letter motion, so the timing gate drops to 80ms
     * and the length cap widens to 28 code points. Shared path unchanged.
     */
    public static boolean shouldUseLetterAnimator(SyllableSegment seg, boolean appleStyle) {
        if (seg == null || isBlank(seg.text)) return false;
        String text = safe(seg.text);
        int codePoints = text.codePointCount(0, text.length());
        if (!appleStyle) {
            boolean multiCjk = codePoints > 1
                    && (SpicyTextDetection.itemJapaneseTest(text)
                    || SpicyTextDetection.itemKoreanTest(text));
            return (seg.totalMs >= 1000 || multiCjk)
                    && codePoints > 0
                    && codePoints <= 12
                    && !SpicyTextDetection.containsRtl(text);
        }
        return seg.totalMs >= 80
                && codePoints > 0
                && codePoints <= 28
                && !SpicyTextDetection.containsRtl(text);
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
