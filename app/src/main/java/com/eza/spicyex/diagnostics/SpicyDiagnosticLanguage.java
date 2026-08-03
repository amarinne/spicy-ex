package com.eza.spicyex.diagnostics;

import com.eza.spicyex.lyrics.SpicyTextDetection;

import java.util.List;
import java.util.Locale;

/** Pure diagnostic-only language fallback. Does not affect lyric processing. */
final class SpicyDiagnosticLanguage {
    private SpicyDiagnosticLanguage() {
    }

    static String resolve(String runtimeLanguage, String providerLanguage, String lyricText) {
        String explicit = normalizeExplicit(runtimeLanguage);
        if (!explicit.isEmpty()) return explicit;
        explicit = normalizeExplicit(providerLanguage);
        if (!explicit.isEmpty()) return explicit;

        List<SpicyTextDetection.Script> scripts =
                SpicyTextDetection.detectPresentScripts(lyricText, "", "");
        if (scripts.size() != 1) return "unknown";
        switch (scripts.get(0)) {
            case JAPANESE:
                return "ja";
            case KOREAN:
                return "ko";
            case CYRILLIC:
                return "cyrillic";
            case GREEK:
                return "el";
            case CHINESE:
            default:
                // Han-only text is ambiguous without provider/document language metadata.
                return "unknown";
        }
    }

    private static String normalizeExplicit(String language) {
        if (language == null) return "";
        String value = language.trim().replace('_', '-').toLowerCase(Locale.ROOT);
        if (value.isEmpty() || "unknown".equals(value) || "none".equals(value)
                || "und".equals(value) || "null".equals(value)) return "";
        String primary = value.split("-", 2)[0];
        SpicyTextDetection.Script script = SpicyTextDetection.scriptFromLanguage(primary, primary);
        if (script == SpicyTextDetection.Script.JAPANESE) return "ja";
        if (script == SpicyTextDetection.Script.CHINESE) return "zh";
        if (script == SpicyTextDetection.Script.KOREAN) return "ko";
        if (script == SpicyTextDetection.Script.CYRILLIC) return "cyrillic";
        if (script == SpicyTextDetection.Script.GREEK) return "el";
        return primary.matches("[a-z]{2,3}") ? primary : "";
    }
}
