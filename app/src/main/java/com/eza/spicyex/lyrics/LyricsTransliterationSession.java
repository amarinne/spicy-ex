package com.eza.spicyex.lyrics;

import com.eza.spicyex.SpotifyPlusConfig;

/** Runtime transliteration toggle state, including per-document JP/CN cycle modes. */
public final class LyricsTransliterationSession {
    private boolean showRomanization;
    private String japaneseReadingMode;
    private String chineseMode;
    private String japaneseModeConfig;
    private String chineseModeConfig;

    public LyricsTransliterationSession(boolean showRomanization, LyricsRenderConfig config) {
        this.showRomanization = showRomanization;
        applyConfig(config);
    }

    public boolean showRomanization() {
        return showRomanization;
    }

    public String japaneseReadingMode() {
        return japaneseReadingMode;
    }

    public String chineseMode() {
        return chineseMode;
    }

    public boolean applyConfig(LyricsRenderConfig config) {
        if (config == null) return false;
        boolean changed = !safe(japaneseModeConfig).equals(safe(config.japaneseModeConfig))
                || !safe(chineseModeConfig).equals(safe(config.chineseModeConfig));
        japaneseModeConfig = safe(config.japaneseModeConfig);
        chineseModeConfig = safe(config.chineseModeConfig);
        if (changed) {
            japaneseReadingMode = safe(config.defaultJapaneseReadingMode);
            chineseMode = safe(config.defaultChineseMode);
        } else {
            if (isBlank(japaneseReadingMode)) japaneseReadingMode = safe(config.defaultJapaneseReadingMode);
            if (isBlank(chineseMode)) chineseMode = safe(config.defaultChineseMode);
        }
        return changed;
    }

    public CycleResult cycle(boolean japaneseDocument, boolean chineseDocument) {
        if (japaneseDocument) {
            cycleJapanese();
            return new CycleResult(showRomanization, "jp transliteration mode");
        }
        if (chineseDocument) {
            cycleChinese();
            return new CycleResult(showRomanization, "cn transliteration mode");
        }
        showRomanization = !showRomanization;
        return new CycleResult(showRomanization, "transliteration mode");
    }

    private void cycleJapanese() {
        if ("cycle".equals(japaneseModeConfig)) {
            if (!showRomanization) {
                showRomanization = true;
                japaneseReadingMode = SpotifyPlusConfig.JP_READING_FURIGANA_ONLY;
            } else if (SpotifyPlusConfig.JP_READING_FURIGANA_ONLY.equals(japaneseReadingMode)) {
                japaneseReadingMode = SpotifyPlusConfig.JP_READING_ROMAJI_ONLY;
            } else if (SpotifyPlusConfig.JP_READING_ROMAJI_ONLY.equals(japaneseReadingMode)) {
                japaneseReadingMode = SpotifyPlusConfig.JP_READING_FURIGANA_ROMAJI;
            } else {
                showRomanization = false;
            }
        } else {
            showRomanization = !showRomanization;
        }
    }

    private void cycleChinese() {
        if ("cycle".equals(chineseModeConfig)) {
            if (!showRomanization) {
                showRomanization = true;
                chineseMode = SpotifyPlusConfig.CHINESE_MODE_PINYIN;
            } else if (SpotifyPlusConfig.CHINESE_MODE_PINYIN.equals(LyricsShellSettings.normalizeChineseMode(chineseMode))) {
                chineseMode = SpotifyPlusConfig.CHINESE_MODE_JYUTPING;
            } else {
                showRomanization = false;
            }
        } else {
            showRomanization = !showRomanization;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isEmpty();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    public static final class CycleResult {
        public final boolean showRomanization;
        public final String reason;

        private CycleResult(boolean showRomanization, String reason) {
            this.showRomanization = showRomanization;
            this.reason = reason;
        }
    }
}
