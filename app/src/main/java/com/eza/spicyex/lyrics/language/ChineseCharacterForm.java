package com.eza.spicyex.lyrics.language;

/** User-selected script projection for Chinese main lyrics. */
public enum ChineseCharacterForm {
    ORIGINAL, SIMPLIFIED, TRADITIONAL;

    public static ChineseCharacterForm fromSetting(String value) {
        if ("simplified".equalsIgnoreCase(value)) return SIMPLIFIED;
        if ("traditional".equalsIgnoreCase(value)) return TRADITIONAL;
        return ORIGINAL;
    }
}
