package com.eza.spicyex.lyrics;

import com.eza.spicyex.SpotifyPlusConfig;

/**
 * Immutable bundle of the per-render romanization choices, threaded through the romanize +
 * cache pipeline instead of passing each value as a separate parameter. Add a field here to
 * introduce a new language option without touching every signature again.
 */
public final class RomanizationOptions {
    public final String chineseMode;       // pinyin / jyutping (effective)
    public final String koreanMode;        // "Letter-by-letter" / "Pronunciation"
    public final boolean chineseTones;     // pinyin tone marks + jyutping tone numbers
    public final String cyrillicMode;      // "Russian" / "Ukrainian"
    public final boolean cyrillicKeepSigns; // keep ь/ъ as prime marks vs drop

    public RomanizationOptions(String chineseMode, String koreanMode, boolean chineseTones,
                               String cyrillicMode, boolean cyrillicKeepSigns) {
        this.chineseMode = chineseMode;
        this.koreanMode = koreanMode;
        this.chineseTones = chineseTones;
        this.cyrillicMode = cyrillicMode;
        this.cyrillicKeepSigns = cyrillicKeepSigns;
    }

    public static final RomanizationOptions DEFAULTS = new RomanizationOptions(
            SpotifyPlusConfig.CHINESE_MODE_PINYIN, "Letter-by-letter", false,
            SpicyRomanizer.CYRILLIC_RUSSIAN, false);

    /** Stable cache-key fragment for the option combination. */
    public String cacheKey() {
        return "cn=" + n(chineseMode)
                + "|kr=" + n(koreanMode)
                + "|tn=" + (chineseTones ? 1 : 0)
                + "|cy=" + n(cyrillicMode)
                + "|cs=" + (cyrillicKeepSigns ? 1 : 0);
    }

    private static String n(String s) {
        return s == null ? "" : s;
    }
}
