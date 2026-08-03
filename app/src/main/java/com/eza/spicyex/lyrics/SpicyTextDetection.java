package com.eza.spicyex.lyrics;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Android port of Spicy fork text detection.
 *
 * Ported from the desktop spicy-lyrics TextDetection and ProcessLyrics helpers.
 */
public final class SpicyTextDetection {
    public enum Script {
        JAPANESE,
        CHINESE,
        KOREAN,
        CYRILLIC,
        GREEK
    }

    private SpicyTextDetection() {
    }

    public static boolean hasRomanizableScript(String text) {
        if (text == null || text.isEmpty()) return false;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (isRomanizableCodePoint(cp)) return true;
            i += Character.charCount(cp);
        }
        return false;
    }

    public static boolean hasResidualScript(String text) {
        if (text == null || text.isEmpty()) return false;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (isKana(cp)
                    || isCjkIdeograph(cp)
                    || isKorean(cp)
                    || isCyrillic(cp)
                    || isGreek(cp)) return true;
            i += Character.charCount(cp);
        }
        return false;
    }

    public static List<Script> detectPresentScripts(String scriptText, String language, String iso2Language) {
        ArrayList<Script> present = new ArrayList<>();
        String text = scriptText == null ? "" : scriptText;

        // Match ProcessLyrics.ts: kana wins over Chinese for mixed kanji/kana Japanese songs.
        if (hasJapaneseText(text)) {
            present.add(Script.JAPANESE);
        } else if (hasChineseText(text)) {
            present.add(Script.CHINESE);
        }
        if (hasKoreanText(text)) present.add(Script.KOREAN);
        if (hasCyrillicText(text)) present.add(Script.CYRILLIC);
        if (hasGreekText(text)) present.add(Script.GREEK);

        Script hint = romanizationBranchFromLanguage(language, iso2Language);
        if (hint != null && !present.contains(hint)) {
            if (hint == Script.JAPANESE || hint == Script.CHINESE) {
                if (!present.contains(Script.JAPANESE) && !present.contains(Script.CHINESE)) {
                    present.add(hint);
                }
            } else {
                present.add(hint);
            }
        }

        ArrayList<Script> ordered = new ArrayList<>();
        addIfPresent(ordered, present, Script.JAPANESE);
        addIfPresent(ordered, present, Script.CHINESE);
        addIfPresent(ordered, present, Script.KOREAN);
        addIfPresent(ordered, present, Script.CYRILLIC);
        addIfPresent(ordered, present, Script.GREEK);
        return ordered;
    }

    public static boolean itemJapaneseTest(String text) {
        return containsCodePoint(text, cp -> isKana(cp) || isCjkIdeograph(cp));
    }

    public static boolean itemChineseTest(String text) {
        return containsCodePoint(text, SpicyTextDetection::isCjkIdeograph);
    }

    public static boolean itemKoreanTest(String text) {
        return containsCodePoint(text, SpicyTextDetection::isKorean);
    }

    public static boolean itemCyrillicTest(String text) {
        return containsCodePoint(text, SpicyTextDetection::isCyrillic);
    }

    public static boolean itemGreekTest(String text) {
        return containsCodePoint(text, SpicyTextDetection::isGreek);
    }

    public static boolean itemDevanagariTest(String text) {
        return containsCodePoint(text, SpicyTextDetection::isDevanagari);
    }

    public static boolean itemGurmukhiTest(String text) {
        return containsCodePoint(text, SpicyTextDetection::isGurmukhi);
    }

    public static boolean itemBengaliTest(String text) {
        return containsCodePoint(text, SpicyTextDetection::isBengali);
    }

    public static boolean hasIndicScript(String text) {
        return containsCodePoint(text, cp -> isDevanagari(cp) || isGurmukhi(cp) || isBengali(cp));
    }

    public static boolean hasCjkIdeograph(String text) {
        return containsCodePoint(text, SpicyTextDetection::isCjkIdeograph);
    }

    public static boolean hasKana(String text) {
        return containsCodePoint(text, SpicyTextDetection::isKana);
    }

    private static void addIfPresent(List<Script> out, List<Script> present, Script script) {
        if (present.contains(script)) out.add(script);
    }

    public static Script scriptFromLanguage(String language, String iso2Language) {
        String lang = language == null ? "" : language.toLowerCase(Locale.ROOT);
        String iso2 = iso2Language == null ? "" : iso2Language.toLowerCase(Locale.ROOT);
        if (lang.equals("jpn") || lang.equals("ja")) return Script.JAPANESE;
        if (lang.equals("cmn") || lang.equals("yue") || lang.equals("zh") || iso2.equals("zh")) return Script.CHINESE;
        if (lang.equals("kor") || lang.equals("ko")) return Script.KOREAN;
        if (isCyrillicLanguage(lang, iso2)) return Script.CYRILLIC;
        if (lang.equals("ell") || lang.equals("el")) return Script.GREEK;
        return null;
    }

    private static Script romanizationBranchFromLanguage(String language, String iso2Language) {
        return scriptFromLanguage(language, iso2Language);
    }

    private static boolean isCyrillicLanguage(String iso3, String iso2) {
        return iso3.equals("bel")
                || iso3.equals("bul")
                || iso3.equals("kaz")
                || iso3.equals("mkd")
                || iso3.equals("rus")
                || iso3.equals("srp")
                || iso3.equals("tgk")
                || iso3.equals("ukr")
                || iso3.equals("ru")
                || iso3.equals("sr")
                || iso3.equals("uk")
                || iso3.equals("bg")
                || iso3.equals("mk")
                || iso3.equals("be")
                || iso2.equals("ky")
                || iso2.equals("mn");
    }

    private static boolean hasJapaneseText(String text) {
        return containsCodePoint(text, SpicyTextDetection::isKana);
    }

    private static boolean hasChineseText(String text) {
        return containsCodePoint(text, SpicyTextDetection::isCjkIdeograph);
    }

    private static boolean hasKoreanText(String text) {
        return containsCodePoint(text, SpicyTextDetection::isKorean);
    }

    private static boolean hasGreekText(String text) {
        return containsCodePoint(text, SpicyTextDetection::isGreek);
    }

    // Match CyrillicTextTest in fork: requires at least 2 consecutive Cyrillic chars.
    private static boolean hasCyrillicText(String text) {
        int consecutive = 0;
        for (int i = 0; text != null && i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (isCyrillic(cp)) {
                consecutive++;
                if (consecutive >= 2) return true;
            } else {
                consecutive = 0;
            }
            i += Character.charCount(cp);
        }
        return false;
    }

    private static boolean isRomanizableCodePoint(int cp) {
        return isKana(cp)
                || isCjkIdeograph(cp)
                || isKorean(cp)
                || isCyrillic(cp)
                || isGreek(cp);
    }

    private static boolean isKana(int cp) {
        return cp >= 0x3040 && cp <= 0x30FF;
    }

    private static boolean isCjkIdeograph(int cp) {
        return (cp >= 0x3400 && cp <= 0x4DBF)
                || (cp >= 0x4E00 && cp <= 0x9FFF)
                || cp == 0x3005;
    }

    private static boolean isKorean(int cp) {
        return (cp >= 0xAC00 && cp <= 0xD7AF)
                || (cp >= 0x1100 && cp <= 0x11FF)
                || (cp >= 0x3130 && cp <= 0x318F)
                || (cp >= 0xA960 && cp <= 0xA97F)
                || (cp >= 0xD7B0 && cp <= 0xD7FF);
    }

    private static boolean isCyrillic(int cp) {
        return (cp >= 0x0400 && cp <= 0x04FF)
                || (cp >= 0x0500 && cp <= 0x052F)
                || (cp >= 0x2DE0 && cp <= 0x2DFF)
                || (cp >= 0xA640 && cp <= 0xA69F);
    }

    private static boolean isGreek(int cp) {
        return (cp >= 0x0370 && cp <= 0x03FF)
                || (cp >= 0x1F00 && cp <= 0x1FFF);
    }

    private static boolean isDevanagari(int cp) {
        return (cp >= 0x0900 && cp <= 0x097F)
                || (cp >= 0xA8E0 && cp <= 0xA8FF);
    }

    private static boolean isGurmukhi(int cp) {
        return cp >= 0x0A00 && cp <= 0x0A7F;
    }

    private static boolean isBengali(int cp) {
        return cp >= 0x0980 && cp <= 0x09FF;
    }

    private interface CodePointPredicate {
        boolean test(int codePoint);
    }

    private static boolean containsCodePoint(String text, CodePointPredicate predicate) {
        if (text == null || text.isEmpty()) return false;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (predicate.test(cp)) return true;
            i += Character.charCount(cp);
        }
        return false;
    }
}
