package com.eza.spicyex.lyrics;

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
            if (isKana(cp)) japanese = true;
            else if (isHangul(cp)) korean = true;
            else if (isCjkIdeograph(cp)) chinese = true;
            else if (isCyrillic(cp)) cyrillic = true;
            else if (isGreek(cp)) greek = true;
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

    private static boolean isKana(int cp) {
        return (cp >= 0x3040 && cp <= 0x30FF);
    }

    private static boolean isHangul(int cp) {
        return (cp >= 0xAC00 && cp <= 0xD7AF)
                || (cp >= 0x1100 && cp <= 0x11FF)
                || (cp >= 0x3130 && cp <= 0x318F)
                || (cp >= 0xA960 && cp <= 0xA97F)
                || (cp >= 0xD7B0 && cp <= 0xD7FF);
    }

    private static boolean isCjkIdeograph(int cp) {
        return Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN;
    }

    private static boolean isCyrillic(int cp) {
        return (cp >= 0x0400 && cp <= 0x052F)
                || (cp >= 0x2DE0 && cp <= 0x2DFF)
                || (cp >= 0xA640 && cp <= 0xA69F);
    }

    private static boolean isGreek(int cp) {
        return (cp >= 0x0370 && cp <= 0x03FF) || (cp >= 0x1F00 && cp <= 0x1FFF);
    }

    private static boolean isIndic(int cp) {
        Character.UnicodeScript script = Character.UnicodeScript.of(cp);
        switch (script) {
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
