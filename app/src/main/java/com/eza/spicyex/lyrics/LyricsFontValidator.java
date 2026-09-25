package com.eza.spicyex.lyrics;

import android.graphics.Paint;
import android.graphics.Typeface;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Checks a user-supplied lyric {@link Typeface} for glyph coverage across every script this app
 * has dedicated reading/romanization support for, so picking a custom font can warn up front
 * about which languages it doesn't actually cover - the same sample text
 * {@code DemoLyricsContent}'s layout-editor preview uses, so "what the demo shows" and "what this
 * check reports" always agree.
 *
 * <p>A missing script here does not mean lyrics in that language will render broken: Android's
 * own per-glyph font fallback substitutes a system font for anything the chosen typeface lacks.
 * It only means the user's own chosen font won't be the one actually drawn for that language.
 */
public final class LyricsFontValidator {
    private LyricsFontValidator() {
    }

    /** Script name -> a short sample containing characters representative of it. */
    private static final Map<String, String> SAMPLES = buildSamples();

    private static Map<String, String> buildSamples() {
        Map<String, String> samples = new LinkedHashMap<>();
        samples.put("English", "Hello");
        samples.put("Japanese", "今日はいい天気ですね");
        samples.put("Chinese", "你好，欢迎来到这里");
        samples.put("Korean", "오늘은 정말 좋은 날이에요");
        samples.put("Russian", "Сегодня прекрасный день");
        samples.put("Greek", "Σήμερα είναι μια όμορφη μέρα");
        samples.put("Arabic", "اليوم يوم جميل");
        return samples;
    }

    /**
     * Scripts the given typeface has no usable coverage for: every non-whitespace codepoint in
     * that script's sample comes back missing from {@link Paint#hasGlyph}. Returns script names
     * in the same order as {@link #SAMPLES}. Never throws - an unusable typeface (or a null one)
     * is reported as missing every script rather than crashing the caller.
     */
    public static List<String> missingScripts(Typeface typeface) {
        List<String> missing = new ArrayList<>();
        Paint paint = new Paint();
        paint.setTypeface(typeface);
        for (Map.Entry<String, String> entry : SAMPLES.entrySet()) {
            if (!hasAnyGlyph(paint, entry.getValue())) missing.add(entry.getKey());
        }
        return missing;
    }

    private static boolean hasAnyGlyph(Paint paint, String sample) {
        int i = 0;
        while (i < sample.length()) {
            int codepoint = sample.codePointAt(i);
            int charCount = Character.charCount(codepoint);
            if (!Character.isWhitespace(codepoint)) {
                String cluster = sample.substring(i, i + charCount);
                try {
                    if (paint.hasGlyph(cluster)) return true;
                } catch (Throwable ignored) {
                    // A typeface that throws on hasGlyph() is as unusable as one with no glyphs.
                }
            }
            i += charCount;
        }
        return false;
    }
}
