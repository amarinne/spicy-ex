package com.eza.spicyex.lyrics.language;

import com.eza.spicyex.lyrics.session.DetectionResult;

/**
 * Which rows a user's reading options deliberately leave without Sound output.
 *
 * <p>Every Sound tier must agree on this answer. The local engine already produces nothing for
 * an Off row, but the lane's bookkeeping and both network tiers only see scripts: left to
 * themselves they treat the intentional absence as a gap — the Google fallback fills it, the
 * AI gap filler bills for it, and the required-rows set keeps the artifact partial forever.
 * Withholding the row up front, in the work lists and in the AI gap decision, keeps one
 * user choice meaning the same thing in all three tiers.
 *
 * <p>Korean Off and Cyrillic Off match whole rows by script, exactly like the local branches
 * they gate (both decide per line, not per span). Chinese Off is narrower on purpose: it
 * matches only rows the local engine routes as Chinese. A Japanese kanji line also carries
 * Han ideographs, but the Japanese engine covers it deterministically, so withholding it
 * would kill legitimate gap-filling for Japanese.
 */
public final class SoundWithholdPolicy {
    private SoundWithholdPolicy() {
    }

    /**
     * @param opts        the reading options the Sound lane runs under; null reads as defaults
     * @param text        the canonical row text
     * @param detection   the row's session detection, or null before detection ran
     * @param docLanguage the document language hint, or "" when unknown
     */
    public static boolean withholdRow(RomanizationOptions opts, String text,
                                      DetectionResult detection, String docLanguage) {
        if (opts == null || text == null || text.isEmpty()) return false;
        if (KoreanDisplayMode.fromSetting(opts.koreanMode) == KoreanDisplayMode.OFF
                && SpicyTextDetection.itemKoreanTest(text)) return true;
        if ("Off".equalsIgnoreCase(opts.cyrillicMode)
                && SpicyTextDetection.itemCyrillicTest(text)) return true;
        return isBlank(opts.chineseMode) && isChineseLine(text, detection, docLanguage);
    }

    /**
     * The local engine's Chinese routing: Han text the session reads as Chinese. Shared so the
     * lane's work lists and the AI gap decision withhold exactly the rows the local branch
     * would have read.
     */
    public static boolean isChineseLine(String text, DetectionResult detection, String docLanguage) {
        return text != null && SpicyTextDetection.hasCjkIdeograph(text)
                && "zh".equals(ReadingLanguagePolicy.language(text, detection,
                        docLanguage == null ? "" : docLanguage));
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
