package com.eza.spicyex.lyrics;

import com.eza.spicyex.lyrics.session.DetectionResult;

/** Target-independent reading route. Han is a script, not a Chinese language declaration. */
public final class ReadingLanguagePolicy {
    private ReadingLanguagePolicy() { }

    public static String language(String text, DetectionResult detection, String sourceHint) {
        if (text == null || text.isEmpty()) return "";
        if (detection != null) {
            // The session owns both decisions and uncertainty. Never override an unresolved row
            // with a whole-song label in a renderer or a segment fallback.
            return detection.hasLanguage() ? detection.language : "";
        }
        if (SpicyTextDetection.hasKana(text)) return "ja";
        if (SpicyTextDetection.itemKoreanTest(text)) return "ko";
        // Before detection, only explicit CJK metadata can route Han. A kana elsewhere in the
        // song is not sufficient; session context resolution is the only owner of that inference.
        String hint = SpicyProcessing.toIso2(sourceHint);
        if (SpicyTextDetection.hasCjkIdeograph(text)
                && ("ja".equals(hint) || "zh".equals(hint))) return hint;
        return "";
    }

    /** Background vocals share a lead sourceLine for timing, but not its language evidence. */
    public static DetectionResult detectionFor(AppliedLine row) {
        return row == null || row.bgLine || row.sourceLine == null ? null : row.sourceLine.detection;
    }

    /** Explicit layout language; und blocks script fallback for unresolved Han. */
    public static String layoutLanguage(AppliedLine row) {
        if (row == null) return "und";
        DetectionResult detection = detectionFor(row);
        String language = language(row.text, detection, "");
        if (!language.isEmpty()) return language;
        if (detection == null && !row.bgLine && row.japaneseReading != null) return "ja";
        return "und";
    }

    public static boolean unresolvedHan(String text, DetectionResult detection) {
        return SpicyTextDetection.hasCjkIdeograph(text)
                && !SpicyTextDetection.hasKana(text)
                && !SpicyTextDetection.itemKoreanTest(text)
                && !"ja".equals(language(text, detection, ""))
                && !"zh".equals(language(text, detection, ""));
    }
}
