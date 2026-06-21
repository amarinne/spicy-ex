package com.eza.spicyex.lyrics;

import com.eza.spicyex.SpotifyPlusConfig;

/** Runtime transliteration toggle state, including per-document JP/CN cycle modes. */
public final class LyricsTransliterationSession {
    private boolean showRomanization;
    private String japaneseReadingMode;
    private String chineseMode;
    private String koreanMode;
    private String cyrillicMode;
    private String japaneseModeConfig;
    private String chineseModeConfig;
    private String koreanModeConfig;
    private String cyrillicModeConfig;

    public LyricsTransliterationSession(boolean showRomanization, LyricsRenderConfig config) {
        this(showRomanization, config, null, null, null, null);
    }

    public LyricsTransliterationSession(boolean showRomanization, LyricsRenderConfig config,
                                       String lastJapaneseReadingMode, String lastChineseMode,
                                       String lastKoreanMode, String lastCyrillicMode) {
        this.showRomanization = showRomanization;
        japaneseReadingMode = safe(lastJapaneseReadingMode);
        chineseMode = safe(lastChineseMode);
        koreanMode = safe(lastKoreanMode);
        cyrillicMode = safe(lastCyrillicMode);
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

    public String koreanMode() {
        return koreanMode;
    }

    public String cyrillicMode() {
        return cyrillicMode;
    }

    public boolean applyConfig(LyricsRenderConfig config) {
        if (config == null) return false;
        boolean changed = !safe(japaneseModeConfig).equals(safe(config.japaneseModeConfig))
                || !safe(chineseModeConfig).equals(safe(config.chineseModeConfig))
                || !safe(koreanModeConfig).equals(safe(config.koreanModeConfig))
                || !safe(cyrillicModeConfig).equals(safe(config.cyrillicModeConfig));
        japaneseModeConfig = safe(config.japaneseModeConfig);
        chineseModeConfig = safe(config.chineseModeConfig);
        koreanModeConfig = safe(config.koreanModeConfig);
        cyrillicModeConfig = safe(config.cyrillicModeConfig);
        if (changed) {
            japaneseReadingMode = cycleOrDefault(japaneseModeConfig, japaneseReadingMode, config.defaultJapaneseReadingMode);
            chineseMode = cycleOrDefault(chineseModeConfig, chineseMode, config.defaultChineseMode);
            koreanMode = cycleOrDefault(koreanModeConfig, koreanMode, config.defaultKoreanMode);
            cyrillicMode = cycleOrDefault(cyrillicModeConfig, cyrillicMode, config.defaultCyrillicMode);
        } else {
            if (isBlank(japaneseReadingMode)) japaneseReadingMode = safe(config.defaultJapaneseReadingMode);
            if (isBlank(chineseMode)) chineseMode = safe(config.defaultChineseMode);
            if (isBlank(koreanMode)) koreanMode = safe(config.defaultKoreanMode);
            if (isBlank(cyrillicMode)) cyrillicMode = safe(config.defaultCyrillicMode);
        }
        return changed;
    }

    public CycleResult cycle(boolean japaneseDocument, boolean chineseDocument,
                             boolean koreanDocument, boolean cyrillicDocument) {
        if (japaneseDocument) {
            cycleJapanese();
            return new CycleResult(showRomanization, "jp transliteration mode");
        }
        if (chineseDocument) {
            cycleChinese();
            return new CycleResult(showRomanization, "cn transliteration mode");
        }
        if (koreanDocument) {
            cycleKorean();
            return new CycleResult(showRomanization, "kr transliteration mode");
        }
        if (cyrillicDocument) {
            cycleCyrillic();
            return new CycleResult(showRomanization, "cy transliteration mode");
        }
        showRomanization = !showRomanization;
        return new CycleResult(showRomanization, "transliteration mode");
    }

    private void cycleJapanese() {
        if ("cycle".equals(japaneseModeConfig)) {
            if (!showRomanization) {
                showRomanization = true;
                if (isBlank(japaneseReadingMode)) japaneseReadingMode = SpotifyPlusConfig.JP_READING_FURIGANA_ROMAJI;
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

    private void cycleKorean() {
        if ("cycle".equals(koreanModeConfig)) {
            if (!showRomanization) {
                showRomanization = true;
                if (isBlank(koreanMode)) koreanMode = "Letter-by-letter";
            } else if ("Letter-by-letter".equals(koreanMode)) {
                koreanMode = SpicyRomanizer.KOREAN_PRONUNCIATION;
            } else {
                showRomanization = false;
            }
        } else if ("Off".equals(koreanModeConfig)) {
            showRomanization = false;
        } else {
            koreanMode = koreanModeConfig;
            showRomanization = !showRomanization;
        }
    }

    private void cycleCyrillic() {
        if ("cycle".equals(cyrillicModeConfig)) {
            if (!showRomanization) {
                showRomanization = true;
                if (isBlank(cyrillicMode)) cyrillicMode = SpicyRomanizer.CYRILLIC_RUSSIAN;
            } else if (SpicyRomanizer.CYRILLIC_RUSSIAN.equals(cyrillicMode)) {
                cyrillicMode = SpicyRomanizer.CYRILLIC_UKRAINIAN;
            } else {
                showRomanization = false;
            }
        } else if ("Off".equals(cyrillicModeConfig)) {
            showRomanization = false;
        } else {
            cyrillicMode = cyrillicModeConfig;
            showRomanization = !showRomanization;
        }
    }

    private void cycleChinese() {
        if ("cycle".equals(chineseModeConfig)) {
            if (!showRomanization) {
                showRomanization = true;
                if (isBlank(chineseMode)) chineseMode = SpotifyPlusConfig.CHINESE_MODE_PINYIN;
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

    private static String cycleOrDefault(String modeConfig, String current, String fallback) {
        if ("cycle".equals(modeConfig) && !isBlank(current)) return current;
        return safe(fallback);
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
