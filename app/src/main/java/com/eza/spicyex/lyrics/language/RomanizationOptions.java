package com.eza.spicyex.lyrics.language;

import com.eza.spicyex.SpotifyPlusConfig;

/**
 * Immutable bundle of the per-render romanization choices, threaded through the romanize +
 * cache pipeline instead of passing each value as a separate parameter. Add a field here to
 * introduce a new language option without touching every signature again.
 */
public final class RomanizationOptions {
    public final String chineseMode;       // pinyin / jyutping (effective)
    public final ChineseOutputStyle chineseOutputStyle;
    public final String koreanMode;        // KoreanDisplayMode value
    public final boolean chineseTones;     // pinyin tone marks + jyutping tone numbers
    public final String cyrillicMode;      // "Russian" / "Ukrainian"
    public final boolean cyrillicKeepSigns; // keep ь/ъ as prime marks vs drop

    public RomanizationOptions(String chineseMode, String koreanMode, boolean chineseTones,
                               String cyrillicMode, boolean cyrillicKeepSigns) {
        this.chineseOutputStyle = ChineseOutputStyle.fromSetting(chineseMode);
        this.chineseMode = canonicalChineseMode(chineseMode);
        this.koreanMode = "Off".equals(koreanMode) ? "Off" : KoreanDisplayMode.valueOfSetting(koreanMode);
        this.chineseTones = chineseTones;
        this.cyrillicMode = cyrillicMode;
        this.cyrillicKeepSigns = cyrillicKeepSigns;
    }

    public RomanizationOptions(String chineseMode, String koreanMode, boolean chineseTones,
                               String cyrillicMode, boolean cyrillicKeepSigns,
                               ChineseOutputStyle chineseOutputStyle) {
        this.chineseOutputStyle = chineseOutputStyle == null
                ? ChineseOutputStyle.fromSetting(chineseMode) : chineseOutputStyle;
        this.chineseMode = canonicalChineseMode(chineseMode);
        this.koreanMode = "Off".equals(koreanMode) ? "Off" : KoreanDisplayMode.valueOfSetting(koreanMode);
        this.chineseTones = chineseTones;
        this.cyrillicMode = cyrillicMode;
        this.cyrillicKeepSigns = cyrillicKeepSigns;
    }

    public static final RomanizationOptions DEFAULTS = new RomanizationOptions(
            SpotifyPlusConfig.CHINESE_MODE_PINYIN, KoreanDisplayMode.RR_STANDARD.value, false,
            SpicyRomanizer.CYRILLIC_RUSSIAN, false);

    /** Stable cache-key fragment for the option combination. */
    public String cacheKey() {
        return "cn=" + n(chineseMode)
                + "|kr=" + n(koreanMode)
                + "|cnStyle=" + chineseOutputStyle.value
                + (chineseOutputStyle == ChineseOutputStyle.EZA ? "@"
                    + ("jyutping".equals(chineseMode) ? ChineseOutputStyle.CANTONESE_REVISION
                    : ChineseOutputStyle.REVISION) : "")
                + (KoreanDisplayMode.VN_PRONUNCIATION.value.equals(koreanMode)
                    ? "|krStyle=eza@" + ChineseOutputStyle.REVISION : "")
                + "|tn=" + (chineseTones ? 1 : 0)
                + "|cy=" + n(cyrillicMode)
                + "|cs=" + (cyrillicKeepSigns ? 1 : 0);
    }

    private static String n(String s) {
        return s == null ? "" : s;
    }

    private static String canonicalChineseMode(String mode) {
        if (mode == null) return "";
        if ("jyutping".equalsIgnoreCase(mode) || "cantonese".equalsIgnoreCase(mode)
                || mode.toLowerCase(java.util.Locale.ROOT).startsWith("jyutping-")) return "jyutping";
        if (mode.toLowerCase(java.util.Locale.ROOT).startsWith("pinyin-")) return "pinyin";
        return mode;
    }
}
