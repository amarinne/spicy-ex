package com.eza.spicyex.lyrics.language;

import java.util.Locale;

/** Display spelling family for a selected Chinese reading provider. */
public enum ChineseOutputStyle {
    CANONICAL("canonical"),
    EZA("eza");

    public static final String REVISION = "eza-2026-10-08.1";
    public static final String CANTONESE_REVISION = "eza-2026-10-25.1";
    public final String value;

    ChineseOutputStyle(String value) {
        this.value = value;
    }

    public static ChineseOutputStyle fromSetting(String value) {
        if (value == null) return CANONICAL;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return "eza".equals(normalized) || "eza式".equals(normalized)
                || normalized.endsWith("-eza") || normalized.endsWith("_eza")
                ? EZA : CANONICAL;
    }
}
