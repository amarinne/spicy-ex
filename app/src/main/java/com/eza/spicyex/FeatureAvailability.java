package com.eza.spicyex;

public final class FeatureAvailability {
    private FeatureAvailability() {
    }

    public static boolean transliterationAvailable() {
        return BuildConfig.TRANSLITERATION_AVAILABLE
                && hasClass("com.atilika.kuromoji.unidic.Tokenizer")
                && hasClass("net.sourceforge.pinyin4j.PinyinHelper");
    }

    public static boolean translationAvailable() {
        return BuildConfig.TRANSLATION_AVAILABLE
                && hasClass("com.github.pemistahl.lingua.api.LanguageDetectorBuilder");
    }

    public static boolean appleFontAvailable() {
        return BuildConfig.APPLE_FONT_AVAILABLE;
    }

    public static String unavailableSummary() {
        return "Full build required";
    }

    private static boolean hasClass(String name) {
        try {
            Class.forName(name, false, FeatureAvailability.class.getClassLoader());
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
