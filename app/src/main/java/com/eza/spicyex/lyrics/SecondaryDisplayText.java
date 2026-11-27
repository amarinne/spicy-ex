package com.eza.spicyex.lyrics;

import java.text.Normalizer;
import java.util.Locale;

import static com.eza.spicyex.lyrics.LyricUtils.isBlank;
import static com.eza.spicyex.lyrics.LyricUtils.safe;

/**
 * Shared display-surface deduplication for transliteration and translation lines.
 *
 * <p>Compares flattened text (NFKC, lowercase, without whitespace, punctuation, or symbols) so any
 * secondary line that differs from the main line — or from a visible reading line — only in case,
 * spacing, or punctuation is suppressed at display time without mutating stored layer artifacts.
 */
public final class SecondaryDisplayText {
    private SecondaryDisplayText() {
    }

    /** Flattens text for display deduplication: NFKC, lowercase, alphanumeric/letter content only. */
    public static String flatten(String value) {
        if (value == null || value.isEmpty()) return "";
        String normalized = Normalizer.isNormalized(value, Normalizer.Form.NFKC)
                ? value : Normalizer.normalize(value, Normalizer.Form.NFKC);
        String lower = normalized.toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(lower.length());
        for (int i = 0; i < lower.length();) {
            int cp = lower.codePointAt(i);
            i += Character.charCount(cp);
            if (isIgnoredForComparison(cp)) continue;
            out.appendCodePoint(cp);
        }
        return out.toString();
    }

    /** True when two texts are equal after ignoring case, whitespace, punctuation, and symbols. */
    public static boolean sameText(String a, String b) {
        if (isBlank(a) || isBlank(b)) return isBlank(a) && isBlank(b);
        return flatten(a).equals(flatten(b));
    }

    /** Full main text for comparison, preserving the un-sliced line on paged surfaces. */
    public static String mainText(AppliedLine line) {
        if (line == null) return "";
        if (line.gradientSource != null && !isBlank(line.gradientSource.text)) {
            return line.gradientSource.text;
        }
        return safe(line.text);
    }

    /** Plan text wins when aligned; AI and other whole-line readings use the legacy line slot. */
    public static String rawReading(AppliedLine line) {
        if (line == null) return "";
        String planned = line.readingRenderPlan == null
                ? "" : safe(line.readingRenderPlan.joinedDisplayText);
        return isBlank(planned) ? safe(line.romanizedText) : planned;
    }

    public static String reading(String mainText, String rawReading) {
        if (isBlank(rawReading)) return "";
        if (!isBlank(mainText) && sameText(mainText, rawReading)) return "";
        return rawReading;
    }

    public static String reading(AppliedLine line) {
        return line == null ? "" : reading(mainText(line), rawReading(line));
    }

    /** Reading text that will actually be shown under the main line for the active display settings. */
    public static String visibleReading(AppliedLine line, boolean showRomanization,
                                        String japaneseReadingMode) {
        if (line == null || line.bgLine || !showRomanization) return "";
        if (LyricsDisplayMode.isJapaneseLine(line)
                && !LyricsDisplayMode.showJapaneseRomaji(japaneseReadingMode)) {
            return "";
        }
        return reading(line);
    }

    public static String translation(String mainText, String rawTranslation) {
        if (isBlank(rawTranslation)) return "";
        if (!isBlank(mainText) && sameText(mainText, rawTranslation)) return "";
        return rawTranslation;
    }

    public static String translation(String mainText, String visibleReading, String rawTranslation) {
        String candidate = translation(mainText, rawTranslation);
        if (isBlank(candidate)) return "";
        if (!isBlank(visibleReading) && sameText(visibleReading, candidate)) return "";
        return candidate;
    }

    public static String translation(AppliedLine line) {
        return line == null ? "" : translation(mainText(line), line.translatedText);
    }

    public static String translation(AppliedLine line, String visibleReading) {
        return line == null ? "" : translation(mainText(line), visibleReading, line.translatedText);
    }

    private static boolean isIgnoredForComparison(int cp) {
        if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) return true;
        switch (Character.getType(cp)) {
            case Character.CONNECTOR_PUNCTUATION:
            case Character.DASH_PUNCTUATION:
            case Character.START_PUNCTUATION:
            case Character.END_PUNCTUATION:
            case Character.INITIAL_QUOTE_PUNCTUATION:
            case Character.FINAL_QUOTE_PUNCTUATION:
            case Character.OTHER_PUNCTUATION:
            case Character.MATH_SYMBOL:
            case Character.CURRENCY_SYMBOL:
            case Character.MODIFIER_SYMBOL:
            case Character.OTHER_SYMBOL:
            case Character.SPACE_SEPARATOR:
            case Character.LINE_SEPARATOR:
            case Character.PARAGRAPH_SEPARATOR:
            case Character.CONTROL:
            case Character.FORMAT:
                return true;
            default:
                return false;
        }
    }
}
