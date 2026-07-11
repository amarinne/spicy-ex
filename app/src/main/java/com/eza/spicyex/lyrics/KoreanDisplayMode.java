package com.eza.spicyex.lyrics;

import java.util.Locale;

/** Explicit Korean extra-line display modes. Raw lyrics remain the primary line. */
public enum KoreanDisplayMode {
    WORD_TRANSLIT("wordTranslit"),
    RR_STANDARD("rrStandard"),
    RR_PRONUNCIATION("rrPronunciation"),
    VN_PRONUNCIATION("vnPronunciation");

    public final String value;

    KoreanDisplayMode(String value) {
        this.value = value;
    }

    public static KoreanDisplayMode fromSetting(String value) {
        return normalizeLegacy(value, null);
    }

    public static KoreanDisplayMode normalizeLegacy(String displayMode, String outputStyle) {
        if (displayMode == null || displayMode.trim().isEmpty()) return RR_STANDARD;
        String raw = displayMode.trim();
        for (KoreanDisplayMode mode : values()) {
            if (mode.value.equals(raw) || mode.name().equalsIgnoreCase(raw)) return mode;
        }
        String lower = raw.toLowerCase(Locale.ROOT);
        if ("blocks".equals(lower) || "letter-by-letter".equals(lower) || "transliteration".equals(lower)) {
            return WORD_TRANSLIT;
        }
        if ("pronunciation".equals(lower)) {
            return "vn".equalsIgnoreCase(outputStyle) ? VN_PRONUNCIATION : RR_PRONUNCIATION;
        }
        return RR_STANDARD;
    }

    public static String valueOfSetting(String value) {
        return fromSetting(value).value;
    }
}
