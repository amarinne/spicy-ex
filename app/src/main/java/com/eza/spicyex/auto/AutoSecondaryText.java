package com.eza.spicyex.auto;

/** Secondary rows are selected independently of the main line's animation. */
public final class AutoSecondaryText {
    private AutoSecondaryText() { }
    public static boolean showReading(String mode) {
        return "Transliteration".equals(mode) || "Both".equals(mode);
    }
    public static boolean showTranslation(String mode) {
        return "Translation".equals(mode) || "Both".equals(mode);
    }
}
