package com.eza.spicyex.lyrics;

import com.eza.spicyex.lyrics.language.KoreanDisplayMode;
import com.eza.spicyex.lyrics.language.SpicyRomanizer;
import com.eza.spicyex.settings.values.BackgroundStyle;
import com.eza.spicyex.settings.values.InterludeIcon;
import com.eza.spicyex.settings.values.LineSpacingMode;
import com.eza.spicyex.settings.values.LineSyncFill;
import com.eza.spicyex.settings.values.LiveCardAnimation;
import com.eza.spicyex.settings.values.LiveCardGlow;
import com.eza.spicyex.settings.values.LiveCardOverflow;
import com.eza.spicyex.settings.values.LiveCardScrollScope;
import com.eza.spicyex.settings.values.LiveCardTransition;
import com.eza.spicyex.settings.values.LyricsFont;
import com.eza.spicyex.settings.values.SecondaryTextMode;
import com.eza.spicyex.settings.values.StoredValue;
import com.eza.spicyex.settings.values.StoredValues;
import com.eza.spicyex.settings.values.TextSizeMode;
import com.eza.spicyex.settings.values.TextWeight;
import com.eza.spicyex.settings.values.WordBounceMode;
import com.eza.spicyex.settings.values.WordBounceStyle;

import android.content.Context;

import com.eza.spicyex.FeatureAvailability;
import com.eza.spicyex.Settings;
import com.eza.spicyex.SpotifyPlusConfig;
import com.eza.spicyex.settings.BlurStrength;

/** Immutable snapshot of renderer-affecting settings plus a small diff helper. */
public final class LyricsRenderConfig {
    public final BackgroundStyle backgroundStyle;
    public final boolean backgroundEnabled;
    public final boolean backgroundAnimated;
    /** Background darkening, 0-100; 0 leaves the background alone. */
    public final int darkening;
    public final boolean lineGradientEnabled;
    public final LyricsMotionConfig motion;
    public final boolean spotlight;
    public final boolean wordBounceEnabled;
    /** The bounce scope: the gate off, or the rows the bounce applies to. */
    public final WordBounceMode wordBounceScope;
    public final WordBounceStyle wordBounceStyle;
    public final boolean appleStyle;
    public final boolean appleLift;
    public final boolean appleDimPassed;
    public final boolean appleCompactText;
    public final boolean glowBlurEnabled;
    public final boolean lineBlurEnabled;
    public final boolean lineBlurHeavy;
    public final float blurQuality;
    public final boolean interludeNoteIcon;
    public final boolean toggleSpinnerEnabled;
    public final boolean attachTransliterationToWords;
    public final boolean transliterationEnabled;
    public final boolean adaptiveSectioningEnabled;
    public final LineSpacingMode lineSpacingMode;
    public final float lineSpacingMultiplier;
    public final TextWeight lyricWeight;
    public final TextWeight liveCardWeight;
    public final LyricsFont lyricsFont;
    public final String lyricsFontCustomPath;
    public final TextSizeMode lyricsTextSizeMode;
    public final float lyricsTextSizeMultiplier;
    public final boolean adaptiveTextSizeEnabled;
    public final TextSizeMode liveCardTextSizeMode;
    public final float liveCardTextSizeMultiplier;
    public final SecondaryTextMode liveCardSecondaryMode;
    public final boolean liveCardShowTransliteration;
    public final boolean liveCardShowTranslation;
    public final boolean liveCardMinimalAnimation;
    public final LiveCardAnimation liveCardAnimationMode;
    public final LiveCardGlow liveCardGlowMode;
    public final LineSyncFill liveCardLineSyncFillMode;
    public final LiveCardTransition liveCardTransitionMode;
    public final LiveCardOverflow liveCardOverflowMode;
    public final LiveCardScrollScope liveCardScrollScope;
    public final LineSyncFill lineSyncFillMode;
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
    public final String translationBackend;
    public final String translationTarget;
    public final TranslationConfig translation;
    public final boolean translationBright;
    public final int syncOffsetMs;

    private LyricsRenderConfig(
            BackgroundStyle backgroundStyle,
            int darkening,
            boolean lineGradientEnabled,
            LyricsMotionConfig motion,
            boolean wordBounceEnabled,
            WordBounceMode wordBounceScope,
            WordBounceStyle wordBounceStyle,
            boolean appleLift,
            boolean glowBlurEnabled,
            boolean lineBlurEnabled,
            boolean lineBlurHeavy,
            float blurQuality,
            boolean interludeNoteIcon,
            boolean toggleSpinnerEnabled,
            boolean attachTransliterationToWords,
            boolean transliterationEnabled,
            boolean adaptiveSectioningEnabled,
            LineSpacingMode lineSpacingMode,
            float lineSpacingMultiplier,
            TextWeight lyricWeight,
            TextWeight liveCardWeight,
            LyricsFont lyricsFont,
            String lyricsFontCustomPath,
            TextSizeMode lyricsTextSizeMode,
            float lyricsTextSizeMultiplier,
            boolean adaptiveTextSizeEnabled,
            TextSizeMode liveCardTextSizeMode,
            float liveCardTextSizeMultiplier,
            SecondaryTextMode liveCardSecondaryMode,
            boolean liveCardShowTransliteration,
            boolean liveCardShowTranslation,
            boolean liveCardMinimalAnimation,
            LiveCardAnimation liveCardAnimationMode,
            LiveCardGlow liveCardGlowMode,
            LineSyncFill liveCardLineSyncFillMode,
            LiveCardTransition liveCardTransitionMode,
            LiveCardOverflow liveCardOverflowMode,
            LiveCardScrollScope liveCardScrollScope,
            LineSyncFill lineSyncFillMode,
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
            TranslationConfig translation,
            boolean translationBright,
            int syncOffsetMs
    ) {
        this.backgroundStyle = backgroundStyle;
        this.backgroundEnabled = backgroundStyle.usesTexture();
        this.backgroundAnimated = backgroundStyle.isAnimated();
        this.darkening = darkening;
        this.lineGradientEnabled = lineGradientEnabled;
        this.motion = motion;
        this.spotlight = motion.isSpotlight();
        this.wordBounceEnabled = wordBounceEnabled;
        this.wordBounceScope = wordBounceScope;
        this.wordBounceStyle = wordBounceStyle;
        this.appleStyle = motion.isApple();
        this.appleLift = appleLift;
        this.appleDimPassed = motion.appleDimPassed();
        this.appleCompactText = motion.appleCompactText();
        this.glowBlurEnabled = glowBlurEnabled;
        this.lineBlurEnabled = lineBlurEnabled;
        this.lineBlurHeavy = lineBlurHeavy;
        this.blurQuality = blurQuality;
        this.interludeNoteIcon = interludeNoteIcon;
        this.toggleSpinnerEnabled = toggleSpinnerEnabled;
        this.attachTransliterationToWords = attachTransliterationToWords;
        this.transliterationEnabled = transliterationEnabled;
        this.adaptiveSectioningEnabled = adaptiveSectioningEnabled;
        this.lineSpacingMode = lineSpacingMode;
        this.lineSpacingMultiplier = lineSpacingMultiplier;
        this.lyricWeight = lyricWeight;
        this.liveCardWeight = liveCardWeight;
        this.lyricsFont = lyricsFont;
        this.lyricsFontCustomPath = safe(lyricsFontCustomPath);
        this.lyricsTextSizeMode = lyricsTextSizeMode;
        this.lyricsTextSizeMultiplier = lyricsTextSizeMultiplier;
        this.adaptiveTextSizeEnabled = adaptiveTextSizeEnabled;
        this.liveCardTextSizeMode = liveCardTextSizeMode;
        this.liveCardTextSizeMultiplier = liveCardTextSizeMultiplier;
        this.liveCardSecondaryMode = liveCardSecondaryMode;
        this.liveCardShowTransliteration = liveCardShowTransliteration;
        this.liveCardShowTranslation = liveCardShowTranslation;
        this.liveCardMinimalAnimation = liveCardMinimalAnimation;
        this.liveCardAnimationMode = liveCardAnimationMode;
        this.liveCardGlowMode = liveCardGlowMode;
        this.liveCardLineSyncFillMode = liveCardLineSyncFillMode;
        this.liveCardTransitionMode = liveCardTransitionMode;
        this.liveCardOverflowMode = liveCardOverflowMode;
        this.liveCardScrollScope = liveCardScrollScope;
        this.lineSyncFillMode = lineSyncFillMode;
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
        this.translation = translation;
        this.translationEnabled = translation.enabled;
        this.translationBackend = safe(translation.backend.storedLabel());
        this.translationTarget = safe(translation.target);
        this.translationBright = translationBright;
        this.syncOffsetMs = syncOffsetMs;
    }

    public static LyricsRenderConfig read(Context context, SpotifyPlusConfig config) {
        return read(context, config, 1f);
    }

    public static LyricsRenderConfig read(Context context, SpotifyPlusConfig config, float lyricsTextBoost) {
        SpotifyPlusConfig cfg = config;
        if (cfg == null && context != null) cfg = SpotifyPlusConfig.from(context);
        LyricsShellSettings shell = new LyricsShellSettings(context, cfg).withTextBoost(lyricsTextBoost);
        LyricsMotionConfig motion = LyricsMotionConfig.read(cfg);
        TranslationConfig translation = TranslationConfig.read(cfg);

        String jp = get(cfg, Settings.JAPANESE_READING_MODE);
        String cn = get(cfg, Settings.CHINESE_MODE);
        String defaultJp = "cycle".equals(jp) ? SpotifyPlusConfig.JP_READING_ROMAJI_ONLY : jp;
        String defaultCn = shell.defaultChineseMode(cn);
        String kr = get(cfg, Settings.KOREAN_ROMANIZATION);
        String defaultKr = "cycle".equals(kr) ? KoreanDisplayMode.RR_STANDARD.value : KoreanDisplayMode.valueOfSetting(kr);
        // Read the live preference, not the config snapshot: the fullscreen chip writes the cycled
        // mode there, and every consumer — including the session-owned Sound lane — must agree with
        // what is on screen.
        String currentKr = shell.currentKoreanMode(kr);
        String cy = get(cfg, Settings.CYRILLIC_MODE);
        String defaultCy = shell.currentCyrillicMode(cy);
        if ("cycle".equals(defaultCy) || defaultCy.isEmpty()) defaultCy = SpicyRomanizer.CYRILLIC_RUSSIAN;
        boolean transliterationEnabled = get(cfg, Settings.TRANSLITERATION_ENABLED);
        boolean translationEnabled = translation.enabled;

        WordBounceMode wordBounceMode = get(cfg, Settings.WORD_BOUNCE, WordBounceMode.class);
        boolean wordBounceEnabled = wordBounceMode != WordBounceMode.OFF;
        WordBounceMode wordBounceScope = wordBounceMode == WordBounceMode.ALL_SYNCED_ROWS
                ? WordBounceMode.ALL_SYNCED_ROWS : WordBounceMode.SYNCED_WORDS_ONLY;
        WordBounceStyle wordBounceStyle = get(cfg, Settings.WORD_BOUNCE_STYLE, WordBounceStyle.class);
        // Dedicated Apple mode (R3): the style flag plus Apple-owned keys only. Shared keys
        // (bounce style, line blur, font, ...) are never rewritten, so no snapshot cycle exists.
        // "Apple lift" is also a shared Bounce style value: it selects the Apple lift motion
        // curve in any animation style without enabling the rest of the Apple stack.
        boolean appleStyle = motion.isApple();
        // The karaoke shader reads its base colour from the view; this is the one place the style is
        // resolved, so it publishes the flag rather than each row having to carry it.
        SpicyAnimatedTextView.setAppleGradientBaseStyle(appleStyle);
        boolean appleLift = appleLiftMotion(wordBounceStyle, appleStyle, get(cfg, Settings.APPLE_LIFT));
        // One blur strength: 0 is off, and the stored value carries the distance curve as well
        // as the percent (see BlurStrength). Every v2.0.41 pair migrated to a value that
        // renders exactly as it did.
        int blurStrength = get(cfg, Settings.LYRICS_BLUR_STRENGTH);

        return new LyricsRenderConfig(
                FeatureAvailability.animatedBackgroundAvailable()
                        ? backgroundStyle(cfg) : BackgroundStyle.DEFAULT,
                get(cfg, Settings.LYRICS_DARKENING),
                get(cfg, Settings.ENABLE_LINE_GRADIENT),
                motion,
                wordBounceEnabled,
                wordBounceScope,
                wordBounceStyle,
                appleLift,
                get(cfg, Settings.ENABLE_GLOW_BLUR),
                blurStrength > 0,
                BlurStrength.standardCurve(blurStrength),
                // The strength is a user-facing artistic knob (Settings#LYRICS_BLUR_STRENGTH,
                // 100 = unchanged), separate from lineBlurQualityMultiplier()'s device-performance
                // tier scaling - folded into the same blurQuality slot since both are plain
                // multipliers over the same curve (LyricsFrameRenderer#mobileLineBlurPx).
                shell.lineBlurQualityMultiplier() * (BlurStrength.percent(blurStrength) / 100f),
                get(cfg, Settings.INTERLUDE_ICON, InterludeIcon.class) == InterludeIcon.NOTE,
                get(cfg, Settings.TOGGLE_PROGRESS_RING),
                shell.attachTransliterationToWordsEnabled(),
                transliterationEnabled,
                get(cfg, Settings.ADAPTIVE_SECTIONING),
                shell.lineSpacingMode(),
                shell.lineSpacingMultiplier(),
                shell.lyricWeight(),
                shell.liveCardWeight(),
                get(cfg, Settings.LYRICS_FONT, LyricsFont.class),
                get(cfg, Settings.LYRICS_FONT_CUSTOM_PATH),
                shell.lyricsTextSizeMode(),
                shell.lyricsTextSizeMultiplier(),
                shell.adaptiveTextSizeEnabled(),
                shell.liveCardTextSizeMode(),
                shell.liveCardTextSizeMultiplier(),
                shell.liveCardSecondaryMode(),
                transliterationEnabled && shell.liveCardShowTransliteration(),
                translationEnabled && shell.liveCardShowTranslation(),
                shell.liveCardAnimationMode() == LiveCardAnimation.MINIMAL,
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
                currentKr,
                get(cfg, Settings.CHINESE_TONES),
                cy,
                defaultCy,
                defaultCy,
                get(cfg, Settings.CYRILLIC_KEEP_SIGNS),
                translation,
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
        return lineSyncFillMode == LineSyncFill.TOP_TO_BOTTOM;
    }

    public boolean lineSyncFillWord() {
        return lineSyncFillMode == LineSyncFill.WORD;
    }

    public boolean lineSyncFillSentence() {
        return lineSyncFillMode == LineSyncFill.SENTENCE;
    }

    public LyricsRenderConfig forLiveCard() { return forLiveCard(false); }

    /** Projected surfaces keep karaoke fill without spatial motion or expensive blur. */
    public LyricsRenderConfig forAndroidAuto() { return forLiveCard(true); }

    private LyricsRenderConfig forLiveCard(boolean androidAuto) {
        boolean minimal = !androidAuto && liveCardAnimationMode == LiveCardAnimation.MINIMAL;
        boolean spotlightCard = !androidAuto && liveCardAnimationMode == LiveCardAnimation.SPOTLIGHT_WORD;
        boolean glow = !androidAuto && liveCardGlowMode != LiveCardGlow.OFF && !spotlightCard;
        LineSyncFill fillMode = androidAuto ? LineSyncFill.SENTENCE : spotlightCard
                ? LineSyncFill.WORD
                : (liveCardAnimationMode == LiveCardAnimation.KARAOKE_FILL
                        ? liveCardLineSyncFillMode : LineSyncFill.TOP_TO_BOTTOM);
        return new LyricsRenderConfig(
                backgroundStyle,
                darkening,
                !minimal,
                motion.forLiveCard(spotlightCard),
                 !androidAuto && wordBounceEnabled,
                 wordBounceScope,
                 androidAuto ? WordBounceStyle.PHRASE_ZOOM : wordBounceStyle,
                 false,
                 glow,
                 false,
                 false,
                 blurQuality,
                interludeNoteIcon,
                toggleSpinnerEnabled,
                attachTransliterationToWords,
                transliterationEnabled,
                adaptiveSectioningEnabled,
                lineSpacingMode,
                lineSpacingMultiplier,
                lyricWeight,
                liveCardWeight,
                lyricsFont,
                lyricsFontCustomPath,
                lyricsTextSizeMode,
                lyricsTextSizeMultiplier,
                adaptiveTextSizeEnabled,
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
                translation,
                translationBright,
                syncOffsetMs
        );
    }

    public Diff diff(LyricsRenderConfig next) {
        return new Diff(this, next);
    }

    /**
     * Apple lift motion selector (pure, unit-tested): the shared "Apple lift" Bounce style
     * value selects the lift curve in any animation style; the Apple-owned toggle applies
     * only under the Apple Music style. No shared key is rewritten either way.
     */
    static boolean appleLiftMotion(WordBounceStyle wordBounceStyle, boolean appleStyle, boolean appleLiftSetting) {
        if (wordBounceStyle == WordBounceStyle.APPLE_LIFT) return true;
        return appleStyle && appleLiftSetting;
    }

    /**
     * The background the runtime draws: the stored style, or the removed animated-background
     * switch when the style was never stored.
     */
    public static BackgroundStyle backgroundStyle(SpotifyPlusConfig config) {
        if (config == null) return BackgroundStyle.DEFAULT;
        if (config.contains(Settings.BACKGROUND_STYLE)) {
            return config.get(Settings.BACKGROUND_STYLE, BackgroundStyle.class);
        }
        return config.get(Settings.ENABLE_BACKGROUND)
                ? BackgroundStyle.ANIMATED_TEXTURE : BackgroundStyle.DEFAULT;
    }

    private static <T> T get(SpotifyPlusConfig config, Settings.Setting<T> setting) {
        return config == null ? setting.defaultValue : config.get(setting);
    }

    private static <E extends Enum<E> & StoredValue> E get(
            SpotifyPlusConfig config, Settings.Setting<String> setting, Class<E> type) {
        return config == null
                ? StoredValues.defaultValue(type) : config.get(setting, type);
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
            boolean fontChanged = oldValue.lyricsFont != next.lyricsFont
                    || changed(oldValue.lyricsFontCustomPath, next.lyricsFontCustomPath);
            boolean weightChanged = oldValue.lyricWeight != next.lyricWeight || fontChanged;
            boolean textSizeChanged = oldValue.lyricsTextSizeMode != next.lyricsTextSizeMode
                    || changed(oldValue.lyricsTextSizeMultiplier, next.lyricsTextSizeMultiplier)
                    || oldValue.adaptiveTextSizeEnabled != next.adaptiveTextSizeEnabled;
            boolean attachChanged = oldValue.attachTransliterationToWords != next.attachTransliterationToWords;
            boolean transliterationChanged = oldValue.transliterationEnabled != next.transliterationEnabled;
            boolean adaptiveSectioningChanged = oldValue.adaptiveSectioningEnabled != next.adaptiveSectioningEnabled;
            boolean spacingChanged = oldValue.lineSpacingMode != next.lineSpacingMode
                    || changed(oldValue.lineSpacingMultiplier, next.lineSpacingMultiplier);
            boolean fillChanged = oldValue.lineSyncFillMode != next.lineSyncFillMode;
            japaneseModeConfigChanged = changed(oldValue.japaneseModeConfig, next.japaneseModeConfig);
            chineseModeConfigChanged = changed(oldValue.chineseModeConfig, next.chineseModeConfig)
                    || changed(oldValue.defaultChineseMode, next.defaultChineseMode);
            koreanModeConfigChanged = changed(oldValue.koreanModeConfig, next.koreanModeConfig)
                    || changed(oldValue.defaultKoreanMode, next.defaultKoreanMode);
            boolean koreanChanged = koreanModeConfigChanged || changed(oldValue.koreanMode, next.koreanMode);
            boolean chineseTonesChanged = oldValue.chineseTones != next.chineseTones;
            cyrillicModeConfigChanged = changed(oldValue.cyrillicModeConfig, next.cyrillicModeConfig);
            boolean cyrillicChanged = cyrillicModeConfigChanged || changed(oldValue.cyrillicMode, next.cyrillicMode)
                    || oldValue.cyrillicKeepSigns != next.cyrillicKeepSigns;
            boolean visualOnlyChanged = oldValue.toggleSpinnerEnabled != next.toggleSpinnerEnabled
                    || oldValue.lineGradientEnabled != next.lineGradientEnabled
                    || oldValue.spotlight != next.spotlight
                     || oldValue.wordBounceEnabled != next.wordBounceEnabled
                     || oldValue.wordBounceScope != next.wordBounceScope
                     || oldValue.wordBounceStyle != next.wordBounceStyle
                     || oldValue.appleStyle != next.appleStyle
                     || oldValue.appleLift != next.appleLift
                     || oldValue.appleDimPassed != next.appleDimPassed
                     || oldValue.glowBlurEnabled != next.glowBlurEnabled
                     || oldValue.lineBlurEnabled != next.lineBlurEnabled
                     || oldValue.lineBlurHeavy != next.lineBlurHeavy
                     || changed(oldValue.blurQuality, next.blurQuality);
            liveCardTextSizeChanged = oldValue.liveCardTextSizeMode != next.liveCardTextSizeMode
                    || changed(oldValue.liveCardTextSizeMultiplier, next.liveCardTextSizeMultiplier);
            liveCardConfigChanged = liveCardTextSizeChanged
                    || oldValue.liveCardWeight != next.liveCardWeight
                    || oldValue.liveCardSecondaryMode != next.liveCardSecondaryMode
                    || oldValue.liveCardShowTransliteration != next.liveCardShowTransliteration
                    || oldValue.liveCardShowTranslation != next.liveCardShowTranslation
                    || oldValue.liveCardMinimalAnimation != next.liveCardMinimalAnimation
                    || oldValue.liveCardAnimationMode != next.liveCardAnimationMode
                    || oldValue.liveCardGlowMode != next.liveCardGlowMode
                    || oldValue.liveCardLineSyncFillMode != next.liveCardLineSyncFillMode
                    || oldValue.liveCardTransitionMode != next.liveCardTransitionMode
                    || oldValue.liveCardOverflowMode != next.liveCardOverflowMode
                    || oldValue.liveCardScrollScope != next.liveCardScrollScope
                    || adaptiveSectioningChanged
                    || fontChanged;
            needsTranslationReprocess = oldValue.translationEnabled != next.translationEnabled
                    || changed(oldValue.translationBackend, next.translationBackend)
                    || changed(oldValue.translationTarget, next.translationTarget);
            needsTimingOnly = oldValue.syncOffsetMs != next.syncOffsetMs;

            needsRowRemount = interludeChanged || weightChanged || textSizeChanged || attachChanged || transliterationChanged
                    || adaptiveSectioningChanged || spacingChanged || fillChanged || japaneseModeConfigChanged
                    || oldValue.translationBright != next.translationBright
                    || oldValue.appleCompactText != next.appleCompactText;
            needsLocalReprocess = transliterationChanged || chineseModeConfigChanged || koreanChanged || chineseTonesChanged || cyrillicChanged;
            needsBackgroundToggle = oldValue.backgroundStyle != next.backgroundStyle
                    || oldValue.darkening != next.darkening;
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
