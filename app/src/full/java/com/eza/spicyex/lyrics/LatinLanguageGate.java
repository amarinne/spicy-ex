package com.eza.spicyex.lyrics;

import com.github.pemistahl.lingua.api.Language;
import com.github.pemistahl.lingua.api.LanguageDetector;
import com.github.pemistahl.lingua.api.LanguageDetectorBuilder;

final class LatinLanguageGate {
    private static final LanguageDetector DETECTOR = LanguageDetectorBuilder.fromLanguages(
            Language.ENGLISH,
            Language.SPANISH,
            Language.FRENCH,
            Language.GERMAN,
            Language.ITALIAN,
            Language.PORTUGUESE,
            Language.DUTCH,
            Language.POLISH,
            Language.SWEDISH,
            Language.DANISH,
            Language.BOKMAL,
            Language.FINNISH,
            Language.TURKISH,
            Language.INDONESIAN,
            Language.MALAY,
            Language.VIETNAMESE,
            Language.RUSSIAN,
            Language.UKRAINIAN,
            Language.BULGARIAN,
            Language.SERBIAN,
            Language.MACEDONIAN,
            Language.BELARUSIAN,
            Language.GREEK,
            Language.JAPANESE,
            Language.KOREAN,
            Language.CHINESE
    ).build();

    private LatinLanguageGate() {
    }

    static boolean lineLooksNonTargetLatin(String compactText, String targetLang) {
        try {
            String detectedIso2 = detectedIso2(compactText);
            return !detectedIso2.equalsIgnoreCase(targetLang);
        } catch (Throwable ignored) {
            return false;
        }
    }

    static boolean lineLooksTargetLatin(String compactText, String targetLang) {
        try {
            return detectedIso2(compactText).equalsIgnoreCase(targetLang);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String detectedIso2(String compactText) {
        Language detected = DETECTOR.detectLanguageOf(compactText);
        return detected.getIsoCode639_1().toString().toLowerCase(java.util.Locale.ROOT);
    }
}
