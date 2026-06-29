package com.eza.spicyex.lyrics;

import android.content.Context;

import com.eza.spicyex.FeatureAvailability;
import com.eza.spicyex.Settings;
import com.eza.spicyex.SpotifyPlusConfig;

/** Immutable snapshot of renderer-affecting settings plus a small diff helper. */
public final class LyricsRenderConfig {
    public final boolean backgroundEnabled;
    public final boolean forceDarkBackground;
    public final boolean lineGradientEnabled;
    public final boolean spotlight;
    public final boolean glowBlurEnabled;
    public final boolean lineBlurEnabled;
    public final float blurQuality;
    public final boolean interludeNoteIcon;
    public final boolean toggleSpinnerEnabled;
    public final boolean attachTransliterationToWords;
    public final boolean transliterationEnabled;
    public final String lineSpacingMode;
    public final float lineSpacingMultiplier;
    public final String lyricWeight;
    public final String liveCardWeight;
    public final String lyricsFont;
    public final String lyricsTextSizeMode;
    public final float lyricsTextSizeMultiplier;
    public final String liveCardTextSizeMode;
    public final float liveCardTextSizeMultiplier;
    public final String liveCardSecondaryMode;
    public final boolean liveCardShowTransliteration;
    public final boolean liveCardShowTranslation;
    public final boolean liveCardMinimalAnimation;
    public final String liveCardAnimationMode;
    public final String liveCardGlowMode;
    public final String liveCardLineSyncFillMode;
    public final String liveCardTransitionMode;
    public final String liveCardOverflowMode;
    public final String liveCardScrollScope;
    public final String lineSyncFillMode;
    public final String japaneseModeConfig;
    public final String defaultJapaneseReadingMode;
    public final String chineseModeConfig;
    public final String defaultChineseMode;
    public final String koreanModeConfig;
    public final String defaultKoreanMode;
    public final String koreanMode;
    public final boolean chineseTones;
    public final String cyrillicModeConfig;
    public final String defaultCyrillicMode;
    public final String cyrillicMode;
    public final boolean cyrillicKeepSigns;
    public final boolean translationEnabled;
    public final String translationTarget;
    public final boolean translationBright;
    public final int syncOffsetMs;

    private LyricsRenderConfig(
            boolean backgroundEnabled,
            boolean forceDarkBackground,
            boolean lineGradientEnabled,
            boolean spotlight,
            boolean glowBlurEnabled,
            boolean lineBlurEnabled,
            float blurQuality,
            boolean interludeNoteIcon,
            boolean toggleSpinnerEnabled,
            boolean attachTransliterationToWords,
            boolean transliterationEnabled,
            String lineSpacingMode,
            float lineSpacingMultiplier,
            String lyricWeight,
            String liveCardWeight,
            String lyricsFont,
            String lyricsTextSizeMode,
            float lyricsTextSizeMultiplier,
            String liveCardTextSizeMode,
            float liveCardTextSizeMultiplier,
            String liveCardSecondaryMode,
            boolean liveCardShowTransliteration,
            boolean liveCardShowTranslation,
            boolean liveCardMinimalAnimation,
            String liveCardAnimationMode,
            String liveCardGlowMode,
            String liveCardLineSyncFillMode,
            String liveCardTransitionMode,
            String liveCardOverflowMode,
            String liveCardScrollScope,
            String lineSyncFillMode,
            String japaneseModeConfig,
            String defaultJapaneseReadingMode,
            String chineseModeConfig,
            String defaultChineseMode,
            String koreanModeConfig,
            String defaultKoreanMode,
            String koreanMode,
            boolean chineseTones,
            String cyrillicModeConfig,
            String defaultCyrillicMode,
            String cyrillicMode,
            boolean cyrillicKeepSigns,
            boolean translationEnabled,
            String translationTarget,
            boolean translationBright,
            int syncOffsetMs
    ) {
        this.backgroundEnabled = backgroundEnabled;
        this.forceDarkBackground = forceDarkBackground;
        this.lineGradientEnabled = lineGradientEnabled;
        this.spotlight = spotlight;
        this.glowBlurEnabled = glowBlurEnabled;
        this.lineBlurEnabled = lineBlurEnabled;
        this.blurQuality = blurQuality;
        this.interludeNoteIcon = interludeNoteIcon;
        this.toggleSpinnerEnabled = toggleSpinnerEnabled;
        this.attachTransliterationToWords = attachTransliterationToWords;
        this.transliterationEnabled = transliterationEnabled;
        this.lineSpacingMode = safe(lineSpacingMode);
        this.lineSpacingMultiplier = lineSpacingMultiplier;
        this.lyricWeight = safe(lyricWeight);
        this.liveCardWeight = safe(liveCardWeight);
        this.lyricsFont = safe(lyricsFont);
        this.lyricsTextSizeMode = safe(lyricsTextSizeMode);
        this.lyricsTextSizeMultiplier = lyricsTextSizeMultiplier;
        this.liveCardTextSizeMode = safe(liveCardTextSizeMode);
        this.liveCardTextSizeMultiplier = liveCardTextSizeMultiplier;
        this.liveCardSecondaryMode = safe(liveCardSecondaryMode);
        this.liveCardShowTransliteration = liveCardShowTransliteration;
        this.liveCardShowTranslation = liveCardShowTranslation;
        this.liveCardMinimalAnimation = liveCardMinimalAnimation;
        this.liveCardAnimationMode = safe(liveCardAnimationMode);
        this.liveCardGlowMode = safe(liveCardGlowMode);
        this.liveCardLineSyncFillMode = safe(liveCardLineSyncFillMode);
        this.liveCardTransitionMode = safe(liveCardTransitionMode);
        this.liveCardOverflowMode = safe(liveCardOverflowMode);
        this.liveCardScrollScope = safe(liveCardScrollScope);
        this.lineSyncFillMode = safe(lineSyncFillMode);
        this.japaneseModeConfig = safe(japaneseModeConfig);
        this.defaultJapaneseReadingMode = safe(defaultJapaneseReadingMode);
        this.chineseModeConfig = safe(chineseModeConfig);
        this.defaultChineseMode = safe(defaultChineseMode);
        this.koreanModeConfig = safe(koreanModeConfig);
        this.defaultKoreanMode = safe(defaultKoreanMode);
        this.koreanMode = safe(koreanMode);
        this.chineseTones = chineseTones;
        this.cyrillicModeConfig = safe(cyrillicModeConfig);
        this.defaultCyrillicMode = safe(defaultCyrillicMode);
        this.cyrillicMode = safe(cyrillicMode);
        this.cyrillicKeepSigns = cyrillicKeepSigns;
        this.translationEnabled = translationEnabled;
        this.translationTarget = safe(translationTarget);
        this.translationBright = translationBright;
        this.syncOffsetMs = syncOffsetMs;
    }

    public static LyricsRenderConfig read(Context context, SpotifyPlusConfig config) {
        SpotifyPlusConfig cfg = config;
        if (cfg == null && context != null) cfg = SpotifyPlusConfig.from(context);
        LyricsShellSettings shell = new LyricsShellSettings(context, cfg);

        boolean transliterationAvailable = FeatureAvailability.transliterationAvailable();
        boolean translationAvailable = FeatureAvailability.translationAvailable();
        String jp = transliterationAvailable ? get(cfg, Settings.JAPANESE_READING_MODE) : "off";
        String cn = transliterationAvailable ? get(cfg, Settings.CHINESE_MODE) : "off";
        String defaultJp = "cycle".equals(jp) ? SpotifyPlusConfig.JP_READING_ROMAJI_ONLY : jp;
        String defaultCn = transliterationAvailable ? shell.defaultChineseMode(cn) : "";
        String kr = transliterationAvailable ? get(cfg, Settings.KOREAN_ROMANIZATION) : "Off";
        String defaultKr = "cycle".equals(kr) ? SpicyRomanizer.KOREAN_PRONUNCIATION : kr;
        String cy = transliterationAvailable ? get(cfg, Settings.CYRILLIC_MODE) : "Off";
        String defaultCy = "cycle".equals(cy) ? SpicyRomanizer.CYRILLIC_RUSSIAN : cy;
        boolean transliterationEnabled = transliterationAvailable && get(cfg, Settings.TRANSLITERATION_ENABLED);
        boolean translationEnabled = translationAvailable && translationEnabled(cfg);

        return new LyricsRenderConfig(
                get(cfg, Settings.ENABLE_BACKGROUND),
                get(cfg, Settings.FORCE_DARK_BACKGROUND),
                get(cfg, Settings.ENABLE_LINE_GRADIENT),
                shell.spotlightAnimation(),
                get(cfg, Settings.ENABLE_GLOW_BLUR),
                get(cfg, Settings.ENABLE_LINE_BLUR),
                shell.lineBlurQualityMultiplier(),
                "note".equals(get(cfg, Settings.INTERLUDE_ICON)),
                get(cfg, Settings.TOGGLE_PROGRESS_RING),
                transliterationAvailable && shell.attachTransliterationToWordsEnabled(),
                transliterationEnabled,
                shell.lineSpacingMode(),
                shell.lineSpacingMultiplier(),
                shell.lyricWeight(),
                shell.liveCardWeight(),
                get(cfg, Settings.LYRICS_FONT),
                shell.lyricsTextSizeMode(),
                shell.lyricsTextSizeMultiplier(),
                shell.liveCardTextSizeMode(),
                shell.liveCardTextSizeMultiplier(),
                shell.liveCardSecondaryMode(),
                transliterationEnabled && shell.liveCardShowTransliteration(),
                translationEnabled && shell.liveCardShowTranslation(),
                "Minimal".equals(shell.liveCardAnimationMode()),
                shell.liveCardAnimationMode(),
                shell.liveCardGlowMode(),
                shell.liveCardLineSyncFillMode(),
                shell.liveCardTransitionMode(),
                shell.liveCardOverflowMode(),
                shell.liveCardScrollScope(),
                shell.lineSyncFillMode(),
                jp,
                defaultJp,
                cn,
                defaultCn,
                kr,
                defaultKr,
                defaultKr,
                transliterationAvailable && get(cfg, Settings.CHINESE_TONES),
                cy,
                defaultCy,
                defaultCy,
                get(cfg, Settings.CYRILLIC_KEEP_SIGNS),
                translationEnabled,
                get(cfg, Settings.TRANSLATION_TARGET),
                "Bright".equals(get(cfg, Settings.TRANSLATION_BRIGHTNESS)),
                get(cfg, Settings.SYNC_OFFSET_MS)
        );
    }

    public long adjustedPositionMs(long playbackPositionMs) {
        return Math.max(0L, playbackPositionMs + syncOffsetMs);
    }

    public long playbackPositionForLyricMs(long lyricPositionMs) {
        return Math.max(0L, lyricPositionMs - syncOffsetMs);
    }

    public boolean lineSyncFillTopDown() {
        return "Top to bottom".equals(lineSyncFillMode);
    }

    public boolean lineSyncFillWord() {
        return "Left to right (word)".equals(lineSyncFillMode);
    }

    public boolean lineSyncFillSentence() {
        return "Left to right (sentence)".equals(lineSyncFillMode);
    }

    public LyricsRenderConfig forLiveCard() {
        boolean minimal = "Minimal".equals(liveCardAnimationMode);
        boolean spotlightCard = "Spotlight word".equals(liveCardAnimationMode);
        boolean glow = !"Off".equals(liveCardGlowMode) && !spotlightCard;
        String fillMode = spotlightCard
                ? "Left to right (word)"
                : ("Karaoke fill".equals(liveCardAnimationMode) ? liveCardLineSyncFillMode : "Top to bottom");
        return new LyricsRenderConfig(
                backgroundEnabled,
                forceDarkBackground,
                !minimal,
                spotlightCard,
                glow,
                false,
                blurQuality,
                interludeNoteIcon,
                toggleSpinnerEnabled,
                attachTransliterationToWords,
                transliterationEnabled,
                lineSpacingMode,
                lineSpacingMultiplier,
                lyricWeight,
                liveCardWeight,
                lyricsFont,
                lyricsTextSizeMode,
                lyricsTextSizeMultiplier,
                liveCardTextSizeMode,
                liveCardTextSizeMultiplier,
                liveCardSecondaryMode,
                liveCardShowTransliteration,
                liveCardShowTranslation,
                minimal,
                liveCardAnimationMode,
                liveCardGlowMode,
                fillMode,
                liveCardTransitionMode,
                liveCardOverflowMode,
                liveCardScrollScope,
                fillMode,
                japaneseModeConfig,
                defaultJapaneseReadingMode,
                chineseModeConfig,
                defaultChineseMode,
                koreanModeConfig,
                defaultKoreanMode,
                koreanMode,
                chineseTones,
                cyrillicModeConfig,
                defaultCyrillicMode,
                cyrillicMode,
                cyrillicKeepSigns,
                translationEnabled,
                translationTarget,
                translationBright,
                syncOffsetMs
        );
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
        public final boolean koreanModeConfigChanged;
        public final boolean cyrillicModeConfigChanged;
        public final boolean liveCardTextSizeChanged;
        public final boolean liveCardConfigChanged;
        public final boolean needsTranslationReprocess;
        public final boolean needsTimingOnly;
        public final boolean onlyTimingChanged;

        private Diff(LyricsRenderConfig oldValue, LyricsRenderConfig next) {
            if (oldValue == null || next == null) {
                hasChanges = oldValue != next;
                needsRowRemount = hasChanges;
                needsLocalReprocess = false;
                needsBackgroundToggle = false;
                needsToggleOnly = false;
                japaneseModeConfigChanged = false;
                chineseModeConfigChanged = false;
                koreanModeConfigChanged = false;
                cyrillicModeConfigChanged = false;
                liveCardTextSizeChanged = false;
                liveCardConfigChanged = false;
                needsTranslationReprocess = false;
                needsTimingOnly = false;
                onlyTimingChanged = false;
                return;
            }

            boolean interludeChanged = oldValue.interludeNoteIcon != next.interludeNoteIcon;
            boolean fontChanged = changed(oldValue.lyricsFont, next.lyricsFont);
            boolean weightChanged = changed(oldValue.lyricWeight, next.lyricWeight) || fontChanged;
            boolean textSizeChanged = changed(oldValue.lyricsTextSizeMode, next.lyricsTextSizeMode)
                    || changed(oldValue.lyricsTextSizeMultiplier, next.lyricsTextSizeMultiplier);
            boolean attachChanged = oldValue.attachTransliterationToWords != next.attachTransliterationToWords;
            boolean transliterationChanged = oldValue.transliterationEnabled != next.transliterationEnabled;
            boolean spacingChanged = changed(oldValue.lineSpacingMode, next.lineSpacingMode)
                    || changed(oldValue.lineSpacingMultiplier, next.lineSpacingMultiplier);
            boolean fillChanged = changed(oldValue.lineSyncFillMode, next.lineSyncFillMode);
            japaneseModeConfigChanged = changed(oldValue.japaneseModeConfig, next.japaneseModeConfig);
            chineseModeConfigChanged = changed(oldValue.chineseModeConfig, next.chineseModeConfig)
                    || changed(oldValue.defaultChineseMode, next.defaultChineseMode);
            koreanModeConfigChanged = changed(oldValue.koreanModeConfig, next.koreanModeConfig);
            boolean koreanChanged = koreanModeConfigChanged || changed(oldValue.koreanMode, next.koreanMode);
            boolean chineseTonesChanged = oldValue.chineseTones != next.chineseTones;
            cyrillicModeConfigChanged = changed(oldValue.cyrillicModeConfig, next.cyrillicModeConfig);
            boolean cyrillicChanged = cyrillicModeConfigChanged || changed(oldValue.cyrillicMode, next.cyrillicMode)
                    || oldValue.cyrillicKeepSigns != next.cyrillicKeepSigns;
            boolean visualOnlyChanged = oldValue.toggleSpinnerEnabled != next.toggleSpinnerEnabled
                    || oldValue.lineGradientEnabled != next.lineGradientEnabled
                    || oldValue.spotlight != next.spotlight
                    || oldValue.glowBlurEnabled != next.glowBlurEnabled
                    || oldValue.lineBlurEnabled != next.lineBlurEnabled
                    || changed(oldValue.blurQuality, next.blurQuality);
            liveCardTextSizeChanged = changed(oldValue.liveCardTextSizeMode, next.liveCardTextSizeMode)
                    || changed(oldValue.liveCardTextSizeMultiplier, next.liveCardTextSizeMultiplier);
            liveCardConfigChanged = liveCardTextSizeChanged
                    || changed(oldValue.liveCardWeight, next.liveCardWeight)
                    || changed(oldValue.liveCardSecondaryMode, next.liveCardSecondaryMode)
                    || oldValue.liveCardShowTransliteration != next.liveCardShowTransliteration
                    || oldValue.liveCardShowTranslation != next.liveCardShowTranslation
                    || oldValue.liveCardMinimalAnimation != next.liveCardMinimalAnimation
                    || changed(oldValue.liveCardAnimationMode, next.liveCardAnimationMode)
                    || changed(oldValue.liveCardGlowMode, next.liveCardGlowMode)
                    || changed(oldValue.liveCardLineSyncFillMode, next.liveCardLineSyncFillMode)
                    || changed(oldValue.liveCardTransitionMode, next.liveCardTransitionMode)
                    || changed(oldValue.liveCardOverflowMode, next.liveCardOverflowMode)
                    || changed(oldValue.liveCardScrollScope, next.liveCardScrollScope)
                    || fontChanged;
            needsTranslationReprocess = oldValue.translationEnabled != next.translationEnabled
                    || changed(oldValue.translationTarget, next.translationTarget);
            needsTimingOnly = oldValue.syncOffsetMs != next.syncOffsetMs;

            needsRowRemount = interludeChanged || weightChanged || textSizeChanged || attachChanged || transliterationChanged
                    || spacingChanged || fillChanged || japaneseModeConfigChanged || oldValue.translationBright != next.translationBright;
            needsLocalReprocess = transliterationChanged || chineseModeConfigChanged || koreanChanged || chineseTonesChanged || cyrillicChanged;
            needsBackgroundToggle = oldValue.backgroundEnabled != next.backgroundEnabled
                    || oldValue.forceDarkBackground != next.forceDarkBackground;
            needsToggleOnly = visualOnlyChanged;
            hasChanges = needsRowRemount || needsLocalReprocess || needsBackgroundToggle || needsToggleOnly
                    || liveCardConfigChanged || needsTranslationReprocess || needsTimingOnly;
            onlyTimingChanged = hasChanges
                    && needsTimingOnly
                    && !needsRowRemount
                    && !needsLocalReprocess
                    && !needsBackgroundToggle
                    && !needsToggleOnly
                    && !liveCardConfigChanged
                    && !needsTranslationReprocess;
        }
    }
}
