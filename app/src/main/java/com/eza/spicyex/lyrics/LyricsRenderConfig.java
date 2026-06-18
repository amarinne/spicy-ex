package com.eza.spicyex.lyrics;

import android.content.Context;

import com.eza.spicyex.Settings;
import com.eza.spicyex.SpotifyPlusConfig;

/** Immutable snapshot of renderer-affecting settings plus a small diff helper. */
public final class LyricsRenderConfig {
    public final boolean backgroundEnabled;
    public final boolean forceDarkBackground;
    public final boolean lineGradientEnabled;
    public final boolean spotlight;
    public final boolean lineBlurEnabled;
    public final float blurQuality;
    public final boolean interludeNoteIcon;
    public final boolean toggleSpinnerEnabled;
    public final boolean attachTransliterationToWords;
    public final String lineSpacingMode;
    public final float lineSpacingMultiplier;
    public final String lyricWeight;
    public final String lyricsTextSizeMode;
    public final float lyricsTextSizeMultiplier;
    public final String liveCardTextSizeMode;
    public final float liveCardTextSizeMultiplier;
    public final String lineSyncFillMode;
    public final String japaneseModeConfig;
    public final String defaultJapaneseReadingMode;
    public final String chineseModeConfig;
    public final String defaultChineseMode;
    public final String koreanMode;
    public final boolean chineseTones;
    public final String cyrillicMode;
    public final boolean cyrillicKeepSigns;
    public final boolean translationEnabled;
    public final String translationTarget;

    private LyricsRenderConfig(
            boolean backgroundEnabled,
            boolean forceDarkBackground,
            boolean lineGradientEnabled,
            boolean spotlight,
            boolean lineBlurEnabled,
            float blurQuality,
            boolean interludeNoteIcon,
            boolean toggleSpinnerEnabled,
            boolean attachTransliterationToWords,
            String lineSpacingMode,
            float lineSpacingMultiplier,
            String lyricWeight,
            String lyricsTextSizeMode,
            float lyricsTextSizeMultiplier,
            String liveCardTextSizeMode,
            float liveCardTextSizeMultiplier,
            String lineSyncFillMode,
            String japaneseModeConfig,
            String defaultJapaneseReadingMode,
            String chineseModeConfig,
            String defaultChineseMode,
            String koreanMode,
            boolean chineseTones,
            String cyrillicMode,
            boolean cyrillicKeepSigns,
            boolean translationEnabled,
            String translationTarget
    ) {
        this.backgroundEnabled = backgroundEnabled;
        this.forceDarkBackground = forceDarkBackground;
        this.lineGradientEnabled = lineGradientEnabled;
        this.spotlight = spotlight;
        this.lineBlurEnabled = lineBlurEnabled;
        this.blurQuality = blurQuality;
        this.interludeNoteIcon = interludeNoteIcon;
        this.toggleSpinnerEnabled = toggleSpinnerEnabled;
        this.attachTransliterationToWords = attachTransliterationToWords;
        this.lineSpacingMode = safe(lineSpacingMode);
        this.lineSpacingMultiplier = lineSpacingMultiplier;
        this.lyricWeight = safe(lyricWeight);
        this.lyricsTextSizeMode = safe(lyricsTextSizeMode);
        this.lyricsTextSizeMultiplier = lyricsTextSizeMultiplier;
        this.liveCardTextSizeMode = safe(liveCardTextSizeMode);
        this.liveCardTextSizeMultiplier = liveCardTextSizeMultiplier;
        this.lineSyncFillMode = safe(lineSyncFillMode);
        this.japaneseModeConfig = safe(japaneseModeConfig);
        this.defaultJapaneseReadingMode = safe(defaultJapaneseReadingMode);
        this.chineseModeConfig = safe(chineseModeConfig);
        this.defaultChineseMode = safe(defaultChineseMode);
        this.koreanMode = safe(koreanMode);
        this.chineseTones = chineseTones;
        this.cyrillicMode = safe(cyrillicMode);
        this.cyrillicKeepSigns = cyrillicKeepSigns;
        this.translationEnabled = translationEnabled;
        this.translationTarget = safe(translationTarget);
    }

    public static LyricsRenderConfig read(Context context, SpotifyPlusConfig config) {
        SpotifyPlusConfig cfg = config;
        if (cfg == null && context != null) cfg = SpotifyPlusConfig.from(context);
        LyricsShellSettings shell = new LyricsShellSettings(context, cfg);

        String jp = get(cfg, Settings.JAPANESE_READING_MODE);
        String cn = get(cfg, Settings.CHINESE_MODE);
        String defaultJp = "cycle".equals(jp) ? SpotifyPlusConfig.JP_READING_FURIGANA_ROMAJI : jp;
        String defaultCn = LyricsShellSettings.normalizeChineseMode(
                "cycle".equals(cn) ? SpotifyPlusConfig.CHINESE_MODE_PINYIN : cn);

        return new LyricsRenderConfig(
                get(cfg, Settings.ENABLE_BACKGROUND),
                get(cfg, Settings.FORCE_DARK_BACKGROUND),
                get(cfg, Settings.ENABLE_LINE_GRADIENT),
                shell.spotlightAnimation(),
                get(cfg, Settings.ENABLE_LINE_BLUR),
                shell.lineBlurQualityMultiplier(),
                "note".equals(get(cfg, Settings.INTERLUDE_ICON)),
                get(cfg, Settings.TOGGLE_PROGRESS_RING),
                shell.attachTransliterationToWordsEnabled(),
                shell.lineSpacingMode(),
                shell.lineSpacingMultiplier(),
                shell.lyricWeight(),
                shell.lyricsTextSizeMode(),
                shell.lyricsTextSizeMultiplier(),
                shell.liveCardTextSizeMode(),
                shell.liveCardTextSizeMultiplier(),
                shell.lineSyncFillMode(),
                jp,
                defaultJp,
                cn,
                defaultCn,
                get(cfg, Settings.KOREAN_ROMANIZATION),
                get(cfg, Settings.CHINESE_TONES),
                get(cfg, Settings.CYRILLIC_MODE),
                get(cfg, Settings.CYRILLIC_KEEP_SIGNS),
                translationEnabled(cfg),
                get(cfg, Settings.TRANSLATION_TARGET)
        );
    }

    public boolean lineSyncFillTopDown() {
        return "Top to bottom".equals(lineSyncFillMode);
    }

    public Diff diff(LyricsRenderConfig next) {
        return new Diff(this, next);
    }

    private static <T> T get(SpotifyPlusConfig config, Settings.Setting<T> setting) {
        return config == null ? setting.defaultValue : config.get(setting);
    }

    private static boolean translationEnabled(SpotifyPlusConfig config) {
        if (config == null) return Settings.TRANSLATION_ENABLED.defaultValue;
        return config.getBoolean(
                Settings.TRANSLATION_ENABLED.key,
                !"disabled".equalsIgnoreCase(config.get(Settings.TRANSLATION_BACKEND))
        );
    }

    private static boolean changed(String a, String b) {
        return !safe(a).equals(safe(b));
    }

    private static boolean changed(float a, float b) {
        return Math.abs(a - b) > 0.0001f;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    public static final class Diff {
        public final boolean hasChanges;
        public final boolean needsRowRemount;
        public final boolean needsLocalReprocess;
        public final boolean needsBackgroundToggle;
        public final boolean needsToggleOnly;
        public final boolean japaneseModeConfigChanged;
        public final boolean chineseModeConfigChanged;
        public final boolean liveCardTextSizeChanged;
        public final boolean needsTranslationReprocess;

        private Diff(LyricsRenderConfig oldValue, LyricsRenderConfig next) {
            if (oldValue == null || next == null) {
                hasChanges = oldValue != next;
                needsRowRemount = hasChanges;
                needsLocalReprocess = false;
                needsBackgroundToggle = false;
                needsToggleOnly = false;
                japaneseModeConfigChanged = false;
                chineseModeConfigChanged = false;
                liveCardTextSizeChanged = false;
                needsTranslationReprocess = false;
                return;
            }

            boolean interludeChanged = oldValue.interludeNoteIcon != next.interludeNoteIcon;
            boolean weightChanged = changed(oldValue.lyricWeight, next.lyricWeight);
            boolean textSizeChanged = changed(oldValue.lyricsTextSizeMode, next.lyricsTextSizeMode)
                    || changed(oldValue.lyricsTextSizeMultiplier, next.lyricsTextSizeMultiplier);
            boolean attachChanged = oldValue.attachTransliterationToWords != next.attachTransliterationToWords;
            boolean spacingChanged = changed(oldValue.lineSpacingMode, next.lineSpacingMode)
                    || changed(oldValue.lineSpacingMultiplier, next.lineSpacingMultiplier);
            boolean fillChanged = changed(oldValue.lineSyncFillMode, next.lineSyncFillMode);
            japaneseModeConfigChanged = changed(oldValue.japaneseModeConfig, next.japaneseModeConfig);
            chineseModeConfigChanged = changed(oldValue.chineseModeConfig, next.chineseModeConfig);
            boolean koreanChanged = changed(oldValue.koreanMode, next.koreanMode);
            boolean chineseTonesChanged = oldValue.chineseTones != next.chineseTones;
            boolean cyrillicChanged = changed(oldValue.cyrillicMode, next.cyrillicMode)
                    || oldValue.cyrillicKeepSigns != next.cyrillicKeepSigns;
            boolean visualOnlyChanged = oldValue.toggleSpinnerEnabled != next.toggleSpinnerEnabled
                    || oldValue.lineGradientEnabled != next.lineGradientEnabled
                    || oldValue.spotlight != next.spotlight
                    || oldValue.lineBlurEnabled != next.lineBlurEnabled
                    || changed(oldValue.blurQuality, next.blurQuality);
            liveCardTextSizeChanged = changed(oldValue.liveCardTextSizeMode, next.liveCardTextSizeMode)
                    || changed(oldValue.liveCardTextSizeMultiplier, next.liveCardTextSizeMultiplier);
            needsTranslationReprocess = oldValue.translationEnabled != next.translationEnabled
                    || changed(oldValue.translationTarget, next.translationTarget);

            needsRowRemount = interludeChanged || weightChanged || textSizeChanged || attachChanged
                    || spacingChanged || fillChanged || japaneseModeConfigChanged;
            needsLocalReprocess = chineseModeConfigChanged || koreanChanged || chineseTonesChanged || cyrillicChanged;
            needsBackgroundToggle = oldValue.backgroundEnabled != next.backgroundEnabled
                    || oldValue.forceDarkBackground != next.forceDarkBackground;
            needsToggleOnly = visualOnlyChanged;
            hasChanges = needsRowRemount || needsLocalReprocess || needsBackgroundToggle || needsToggleOnly
                    || liveCardTextSizeChanged || needsTranslationReprocess;
        }
    }
}
