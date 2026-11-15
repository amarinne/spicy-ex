package com.eza.spicyex.lyrics.language;

/** Katakana-to-hiragana conversion, the single rule every Japanese reading path shares. */
public final class Kana {
    private Kana() {
    }

    /** Hiragana form of one katakana character; every other character is returned unchanged. */
    public static char hiragana(char c) {
        return c >= 'ァ' && c <= 'ヶ' ? (char) (c - 0x60) : c;
    }

    public static String toHiragana(String text) {
        if (text == null || text.isEmpty()) return "";
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) out.append(hiragana(text.charAt(i)));
        return out.toString();
    }
}
