package com.eza.spicyex.lyrics.language;

import com.eza.spicyex.SpotifyPlusConfig;
import com.eza.spicyex.lyrics.BackgroundLine;
import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.lyrics.LyricsLine;
import com.eza.spicyex.settings.values.CyrillicMode;

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
    public final String cyrillicMode;      // configured mode or resolved reading preset
    public final boolean cyrillicKeepSigns; // keep ь/ъ as prime marks vs drop

    public RomanizationOptions(String chineseMode, String koreanMode, boolean chineseTones,
                               String cyrillicMode, boolean cyrillicKeepSigns) {
        this.chineseMode = canonicalChineseMode(chineseMode);
        this.chineseOutputStyle = available(this.chineseMode, ChineseOutputStyle.fromSetting(chineseMode));
        this.koreanMode = "Off".equals(koreanMode) ? "Off" : KoreanDisplayMode.valueOfSetting(koreanMode);
        this.chineseTones = chineseTones;
        this.cyrillicMode = cyrillicMode;
        this.cyrillicKeepSigns = cyrillicKeepSigns;
    }

    public RomanizationOptions(String chineseMode, String koreanMode, boolean chineseTones,
                               String cyrillicMode, boolean cyrillicKeepSigns,
                               ChineseOutputStyle chineseOutputStyle) {
        this.chineseMode = canonicalChineseMode(chineseMode);
        this.chineseOutputStyle = available(this.chineseMode, chineseOutputStyle == null
                ? ChineseOutputStyle.fromSetting(chineseMode) : chineseOutputStyle);
        this.koreanMode = "Off".equals(koreanMode) ? "Off" : KoreanDisplayMode.valueOfSetting(koreanMode);
        this.chineseTones = chineseTones;
        this.cyrillicMode = cyrillicMode;
        this.cyrillicKeepSigns = cyrillicKeepSigns;
    }

    public static final RomanizationOptions DEFAULTS = new RomanizationOptions(
            SpotifyPlusConfig.CHINESE_MODE_PINYIN, KoreanDisplayMode.RR_STANDARD.value, false,
            SpicyRomanizer.CYRILLIC_RUSSIAN, false);

    /** Resolve automatic presets once for the document, including its timed reading segments. */
    public RomanizationOptions forDocument(LyricsDocument doc) {
        boolean autoChinese = ChineseAutoMode.isAuto(chineseMode);
        boolean autoCyrillic = CyrillicMode.AUTO.storedLabel().equals(cyrillicMode);
        if (!autoChinese && !autoCyrillic) return this;
        return new RomanizationOptions(ChineseAutoMode.resolve(chineseMode, doc), koreanMode,
                chineseTones, autoCyrillic ? detectCyrillicMode(doc) : cyrillicMode,
                cyrillicKeepSigns, chineseOutputStyle);
    }

    private static String detectCyrillicMode(LyricsDocument doc) {
        if (doc != null) {
            for (LyricsLine line : doc.lines) {
                if (line == null) continue;
                if (hasUkrainianLetters(line.text)) return SpicyRomanizer.CYRILLIC_UKRAINIAN;
                for (BackgroundLine background : line.backgroundLines) {
                    if (background != null && hasUkrainianLetters(background.text)) {
                        return SpicyRomanizer.CYRILLIC_UKRAINIAN;
                    }
                }
            }
        }
        return SpicyRomanizer.CYRILLIC_RUSSIAN;
    }

    private static boolean hasUkrainianLetters(String text) {
        if (text == null) return false;
        for (int i = 0; i < text.length(); i++) {
            if ("іїєґІЇЄҐ".indexOf(text.charAt(i)) >= 0) return true;
        }
        return false;
    }

    /** Stable cache-key fragment for the option combination. */
    public String cacheKey() {
        return "cn=" + n(chineseMode)
                + ("jyutping".equals(chineseMode) ? "@" + JyutpingRomanizer.REVISION : "")
                + "|kr=" + n(koreanMode)
                + "|cnStyle=" + chineseOutputStyle.value
                + (chineseOutputStyle == ChineseOutputStyle.EZA
                    ? "@" + ChineseOutputStyle.REVISION : "")
                + (KoreanDisplayMode.VN_PRONUNCIATION.value.equals(koreanMode)
                    ? "|krStyle=eza@" + ChineseOutputStyle.KOREAN_REVISION : "")
                + "|zhuyin=" + ZhuyinResolver.REVISION
                + "|tn=" + (chineseTones ? 1 : 0)
                + "|cy=" + n(cyrillicMode)
                + "|cs=" + (cyrillicKeepSigns ? 1 : 0);
    }

    private static String n(String s) {
        return s == null ? "" : s;
    }

    /**
     * Mandarin eza式 needs the language model pack. Without it the row shows Standard Pinyin, and
     * the cache key says so, so installing the pack recomputes the row in eza式.
     */
    private static ChineseOutputStyle available(String canonicalMode, ChineseOutputStyle style) {
        if (style == ChineseOutputStyle.EZA && SpotifyPlusConfig.CHINESE_MODE_PINYIN.equals(canonicalMode)
                && !MandarinReadings.available()) {
            return ChineseOutputStyle.CANONICAL;
        }
        return style;
    }

    private static String canonicalChineseMode(String mode) {
        if (mode == null) return "";
        if ("jyutping".equalsIgnoreCase(mode) || "cantonese".equalsIgnoreCase(mode)
                || mode.toLowerCase(java.util.Locale.ROOT).startsWith("jyutping-")) return "jyutping";
        if (mode.toLowerCase(java.util.Locale.ROOT).startsWith("pinyin-")) return "pinyin";
        return mode;
    }
}
