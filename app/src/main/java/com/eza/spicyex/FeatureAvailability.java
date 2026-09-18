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
                && hasClass("org.apache.tika.langdetect.charsoup.core.CharSoupModel");
    }

    public static boolean appleFontAvailable() {
        return BuildConfig.APPLE_FONT_AVAILABLE;
    }

    /**
     * The ambient background is an AGSL {@code RuntimeShader}, which is API 33+. Unlike the flags
     * above this is a device limit, not a build flavour one, so it can never become true on an
     * older device — pre-33 devices get no animated background at all rather than a lesser mimic.
     */
    public static boolean animatedBackgroundAvailable() {
        return android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU;
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
