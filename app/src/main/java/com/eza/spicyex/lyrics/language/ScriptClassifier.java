package com.eza.spicyex.lyrics.language;

/**
 * Lightweight script routing for language detection.
 *
 * <p>Identifies the script family of a lyric line with code-point ranges only — no model, no
 * allocation beyond the scan itself. The result lets {@link com.eza.spicyex.lyrics.session
 * .DetectionArtifact} rows skip detector work for obvious scripts and lets the detector manager
 * load only the language family a line can belong to.
 *
 * <p>This is a routing optimization, not a language decision: ambiguous Latin text still needs the
 * detector, and an Indic line carries no language unless a model supports it.
 */
public final class ScriptClassifier {
    public enum ScriptClass {
        JAPANESE,
        KOREAN,
        CHINESE,
        CYRILLIC,
        GREEK,
        INDIC,
        LATIN,
        OTHER
    }

    private ScriptClassifier() {
    }

    /** Strongest script in {@code text}; script order matches the romanization branches. */
    public static ScriptClass classify(String text) {
        if (text == null || text.isEmpty()) return ScriptClass.OTHER;
        boolean japanese = false;
        boolean korean = false;
        boolean chinese = false;
        boolean cyrillic = false;
        boolean greek = false;
        boolean indic = false;
        boolean latin = false;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (!Character.isLetter(cp)) continue;
            // Scan the whole line: a single kana makes a kanji-heavy line Japanese, so the
            // first character must not decide the script.
            if (SpicyTextDetection.isKana(cp)) japanese = true;
            else if (SpicyTextDetection.isKorean(cp)) korean = true;
            else if (SpicyTextDetection.isCjkIdeograph(cp)) chinese = true;
            else if (SpicyTextDetection.isCyrillic(cp)) cyrillic = true;
            else if (SpicyTextDetection.isGreek(cp)) greek = true;
            else if (isIndic(cp)) indic = true;
            else if (Character.UnicodeScript.of(cp) == Character.UnicodeScript.LATIN) latin = true;
        }
        if (japanese) return ScriptClass.JAPANESE;
        if (korean) return ScriptClass.KOREAN;
        if (chinese) return ScriptClass.CHINESE;
        if (cyrillic) return ScriptClass.CYRILLIC;
        if (greek) return ScriptClass.GREEK;
        if (indic) return ScriptClass.INDIC;
        if (latin) return ScriptClass.LATIN;
        return ScriptClass.OTHER;
    }

    /** True when the script can map to exactly one language without model work. */
    public static boolean isUnambiguous(ScriptClass scriptClass) {
        return scriptClass == ScriptClass.JAPANESE
                || scriptClass == ScriptClass.KOREAN
                || scriptClass == ScriptClass.GREEK;
    }

    public static boolean hasJapaneseKana(String text) {
        return SpicyTextDetection.hasKana(text);
    }

    /** Any Indic script the detector's model family can serve. */
    private static boolean isIndic(int cp) {
        switch (Character.UnicodeScript.of(cp)) {
            case DEVANAGARI:
            case GURMUKHI:
            case BENGALI:
            case GUJARATI:
            case ORIYA:
            case TAMIL:
            case TELUGU:
            case KANNADA:
            case MALAYALAM:
            case SINHALA:
                return true;
            default:
                return false;
        }
    }
}
