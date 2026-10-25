package com.eza.spicyex.lyrics;

import com.eza.spicyex.lyrics.language.KoreanDisplayMode;

import android.content.Context;
import android.content.SharedPreferences;

import com.eza.spicyex.Settings;
import com.eza.spicyex.settings.values.AnimationStyle;
import com.eza.spicyex.settings.values.BackgroundQuality;
import com.eza.spicyex.settings.values.ChineseMode;
import com.eza.spicyex.settings.values.CyrillicMode;
import com.eza.spicyex.settings.values.LineSpacingMode;
import com.eza.spicyex.settings.values.LineSyncFill;
import com.eza.spicyex.settings.values.LiveCardAnimation;
import com.eza.spicyex.settings.values.LiveCardGlow;
import com.eza.spicyex.settings.values.LiveCardOverflow;
import com.eza.spicyex.settings.values.LiveCardScrollScope;
import com.eza.spicyex.settings.values.LiveCardTransition;
import com.eza.spicyex.settings.values.SecondaryTextMode;
import com.eza.spicyex.settings.values.StoredValues;
import com.eza.spicyex.settings.values.TextSizeMode;
import com.eza.spicyex.settings.values.TextWeight;
import com.eza.spicyex.SpotifyPlusConfig;
import static com.eza.spicyex.lyrics.LyricUtils.safe;

/**
 * Owns normalized runtime reads for the fullscreen and now-playing lyrics surfaces.
 * Does not own schema/default declaration (Settings) or panel writes (SettingsStore).
 */
public final class LyricsShellSettings {
    private static final String PREFS_MAIN = SpotifyPlusConfig.PREFS_NAME;

    private final Context context;
    private final SpotifyPlusConfig config;

    public LyricsShellSettings(Context context, SpotifyPlusConfig config) {
        this.context = context;
        this.config = config;
    }

    public boolean attachTransliterationToWordsEnabled() {
        boolean fallback = config != null && config.get(Settings.ALIGNED_PER_WORD_ROMAJI);
        try {
            SharedPreferences prefs = prefs();
            if (prefs != null && prefs.contains(Settings.ALIGNED_PER_WORD_ROMAJI.key)) {
                return prefs.getBoolean(Settings.ALIGNED_PER_WORD_ROMAJI.key, fallback);
            }
        } catch (Throwable ignored) {
        }
        return fallback;
    }

    /** 0 means the controls remain visible; otherwise the whole-second hide delay. */
    public int fullscreenControlsTimeoutSeconds() {
        String fallback = config == null ? Settings.FULLSCREEN_CONTROLS.defaultValue
                : config.get(Settings.FULLSCREEN_CONTROLS);
        try {
            SharedPreferences prefs = prefs();
            if (prefs != null && prefs.contains(Settings.FULLSCREEN_CONTROLS.key)) {
                String value = prefs.getString(Settings.FULLSCREEN_CONTROLS.key, fallback);
                return parseFullscreenControlsSeconds(value);
            }
        } catch (Throwable ignored) { }
        return parseFullscreenControlsSeconds(fallback);
    }

    /** Accepts all canonical values plus the four values shipped by the old preset control. */
    public static int parseFullscreenControlsSeconds(String value) {
        if (value == null || "Always".equalsIgnoreCase(value)
                || "Always on".equalsIgnoreCase(value)) return 0;
        String digits = value.trim().replaceAll("[^0-9]", "");
        if (digits.isEmpty()) return 0;
        try {
            return Math.max(1, Math.min(30, Integer.parseInt(digits)));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    public static String fullscreenControlsValue(int seconds) {
        if (seconds <= 0 || seconds > 30) return "Always";
        return seconds == 1 ? "1 second" : seconds + " seconds";
    }

    public LineSpacingMode lineSpacingMode() {
        // Through the config, not the raw key: this is a per-orientation setting (see Settings).
        return StoredValues.fromStored(LineSpacingMode.class,
                config == null ? LineSpacingMode.MORE.storedLabel() : config.get(Settings.LINE_SPACING));
    }

    public float lineSpacingMultiplier() {
        LineSpacingMode spacing = lineSpacingMode();
        if (spacing == LineSpacingMode.CUSTOM) {
            int hundredths = config == null ? Settings.LINE_SPACING_CUSTOM.defaultValue
                    : config.get(Settings.LINE_SPACING_CUSTOM);
            return Math.max(0f, Math.min(5f, hundredths / 100f));
        }
        // Widened spread so the setting is clearly visible (previously 0.82–1.45 barely moved the
        // ~10/13dp row padding it scales). Tune freely.
        // Dialed back from the old 0.7–4.2 spread (too coarse a jump per step) to finer increments.
        switch (spacing) {
            case COMPACT: return 0.8f;
            case SPACIOUS: return 1.5f;
            case MORE: return 2.0f;
            case MAX: return 2.5f;
            default: return 1.1f;
        }
    }

    public TextWeight lyricWeight() {
        return readWeight(Settings.LYRICS_WEIGHT);
    }

    public TextWeight liveCardWeight() {
        return readWeight(Settings.LIVE_CARD_WEIGHT);
    }

    private TextWeight readWeight(Settings.Setting<String> setting) {
        String fallback = config == null ? "" : config.get(setting);
        try {
            SharedPreferences prefs = prefs();
            if (prefs != null && prefs.contains(setting.key)) {
                return StoredValues.fromStored(TextWeight.class, prefs.getString(setting.key, fallback));
            }
        } catch (Throwable ignored) {
        }
        return StoredValues.fromStored(TextWeight.class, fallback);
    }

    public TextSizeMode lyricsTextSizeMode() {
        return StoredValues.fromStored(TextSizeMode.class,
                config == null ? "" : config.get(Settings.LYRICS_TEXT_SIZE));
    }

    /**
     * A landscape screen is about half as tall, so a size chosen in portrait shows only two or
     * three lines there. Until the user sets a landscape size of their own (see
     * Settings#landscapeKey), landscape uses the portrait choice scaled to fit instead.
     */
    public static final float LANDSCAPE_FIT_SCALE = 0.78f;

    /** Scales the lyrics (and with them the secondary lines) on surfaces that are laid out large
     *  and shown small, such as the landscape picture-in-picture window. */
    private float textBoost = 1f;

    public LyricsShellSettings withTextBoost(float boost) {
        textBoost = boost;
        return this;
    }

    public float lyricsTextSizeMultiplier() {
        return rawLyricsTextSizeMultiplier() * textBoost;
    }

    private float rawLyricsTextSizeMultiplier() {
        float base;
        if (lyricsTextSizeMode() == TextSizeMode.CUSTOM) {
            int hundredths = config == null ? Settings.LYRICS_TEXT_SIZE_CUSTOM.defaultValue
                    : config.get(Settings.LYRICS_TEXT_SIZE_CUSTOM);
            base = Math.max(0f, Math.min(5f, hundredths / 100f));
        } else {
            base = lyricsTextSizeMode().multiplier();
        }
        return inheritsPortraitSize() ? base * LANDSCAPE_FIT_SCALE : base;
    }

    private boolean inheritsPortraitSize() {
        try {
            String landscapeKey = Settings.landscapeKey(context, Settings.LYRICS_TEXT_SIZE);
            if (landscapeKey == null) return false;
            SharedPreferences prefs = prefs();
            return prefs == null || !prefs.contains(landscapeKey);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public boolean adaptiveTextSizeEnabled() {
        boolean fallback = config == null
                ? Settings.LYRICS_ADAPTIVE_TEXT_SIZE.defaultValue
                : config.get(Settings.LYRICS_ADAPTIVE_TEXT_SIZE);
        try {
            SharedPreferences prefs = prefs();
            if (prefs != null && prefs.contains(Settings.LYRICS_ADAPTIVE_TEXT_SIZE.key)) {
                return prefs.getBoolean(Settings.LYRICS_ADAPTIVE_TEXT_SIZE.key, fallback);
            }
        } catch (Throwable ignored) {
        }
        return fallback;
    }

    /** Independent size mode for the now-playing live card (Settings.LIVE_CARD_TEXT_SIZE). */
    public TextSizeMode liveCardTextSizeMode() {
        String fallback = config == null ? "" : config.get(Settings.LIVE_CARD_TEXT_SIZE);
        try {
            SharedPreferences prefs = prefs();
            if (prefs != null && prefs.contains(Settings.LIVE_CARD_TEXT_SIZE.key)) {
                return StoredValues.fromStored(TextSizeMode.class,
                        prefs.getString(Settings.LIVE_CARD_TEXT_SIZE.key, fallback));
            }
        } catch (Throwable ignored) {
        }
        return StoredValues.fromStored(TextSizeMode.class, fallback);
    }

    public float liveCardTextSizeMultiplier() {
        if (liveCardTextSizeMode() == TextSizeMode.CUSTOM) {
            int fallback = config == null ? Settings.LIVE_CARD_TEXT_SIZE_CUSTOM.defaultValue
                    : config.get(Settings.LIVE_CARD_TEXT_SIZE_CUSTOM);
            int hundredths = fallback;
            try {
                SharedPreferences prefs = prefs();
                if (prefs != null && prefs.contains(Settings.LIVE_CARD_TEXT_SIZE_CUSTOM.key)) {
                    hundredths = prefs.getInt(Settings.LIVE_CARD_TEXT_SIZE_CUSTOM.key, fallback);
                }
            } catch (Throwable ignored) {
            }
            return Math.max(0f, Math.min(5f, hundredths / 100f));
        }
        return liveCardTextSizeMode().multiplier();
    }

    public SecondaryTextMode liveCardSecondaryMode() {
        String fallback = config == null ? Settings.LIVE_CARD_SECONDARY_MODE.defaultValue
                : config.get(Settings.LIVE_CARD_SECONDARY_MODE);
        try {
            SharedPreferences prefs = prefs();
            if (prefs != null && prefs.contains(Settings.LIVE_CARD_SECONDARY_MODE.key)) {
                return StoredValues.fromStored(SecondaryTextMode.class,
                        prefs.getString(Settings.LIVE_CARD_SECONDARY_MODE.key, fallback));
            }
            if (prefs != null && prefs.contains(Settings.LIVE_CARD_SHOW_TRANSLITERATION.key)
                    && prefs.getBoolean(Settings.LIVE_CARD_SHOW_TRANSLITERATION.key, false)) {
                return SecondaryTextMode.TRANSLITERATION;
            }
        } catch (Throwable ignored) {
        }
        return StoredValues.fromStored(SecondaryTextMode.class, fallback);
    }

    public boolean liveCardShowTransliteration() {
        return liveCardSecondaryMode().showsTransliteration();
    }

    public boolean liveCardShowTranslation() {
        return liveCardSecondaryMode().showsTranslation();
    }

    public LiveCardAnimation liveCardAnimationMode() {
        String fallback = config == null ? "" : config.get(Settings.LIVE_CARD_ANIMATION);
        try {
            SharedPreferences prefs = prefs();
            if (prefs != null && prefs.contains(Settings.LIVE_CARD_ANIMATION.key)) {
                return StoredValues.fromStored(LiveCardAnimation.class,
                        prefs.getString(Settings.LIVE_CARD_ANIMATION.key, fallback));
            }
        } catch (Throwable ignored) {
        }
        return StoredValues.fromStored(LiveCardAnimation.class, fallback);
    }

    public LiveCardGlow liveCardGlowMode() {
        String fallback = config == null ? "" : config.get(Settings.LIVE_CARD_GLOW);
        try {
            SharedPreferences prefs = prefs();
            if (prefs != null && prefs.contains(Settings.LIVE_CARD_GLOW.key)) {
                return StoredValues.fromStored(LiveCardGlow.class,
                        prefs.getString(Settings.LIVE_CARD_GLOW.key, fallback));
            }
        } catch (Throwable ignored) {
        }
        return StoredValues.fromStored(LiveCardGlow.class, fallback);
    }

    public LineSyncFill liveCardLineSyncFillMode() {
        String fallback = config == null ? "" : config.get(Settings.LIVE_CARD_LINE_SYNC_FILL);
        try {
            SharedPreferences prefs = prefs();
            if (prefs != null && prefs.contains(Settings.LIVE_CARD_LINE_SYNC_FILL.key)) {
                return StoredValues.fromStored(LineSyncFill.class,
                        prefs.getString(Settings.LIVE_CARD_LINE_SYNC_FILL.key, fallback));
            }
        } catch (Throwable ignored) {
        }
        return StoredValues.fromStored(LineSyncFill.class, fallback);
    }

    public LiveCardTransition liveCardTransitionMode() {
        String fallback = config == null ? "" : config.get(Settings.LIVE_CARD_TRANSITION);
        try {
            SharedPreferences prefs = prefs();
            if (prefs != null && prefs.contains(Settings.LIVE_CARD_TRANSITION.key)) {
                return StoredValues.fromStored(LiveCardTransition.class,
                        prefs.getString(Settings.LIVE_CARD_TRANSITION.key, fallback));
            }
        } catch (Throwable ignored) {
        }
        return StoredValues.fromStored(LiveCardTransition.class, fallback);
    }

    public LiveCardOverflow liveCardOverflowMode() {
        String fallback = config == null ? "" : config.get(Settings.LIVE_CARD_OVERFLOW);
        try {
            SharedPreferences prefs = prefs();
            if (prefs != null && prefs.contains(Settings.LIVE_CARD_OVERFLOW.key)) {
                return StoredValues.fromStored(LiveCardOverflow.class,
                        prefs.getString(Settings.LIVE_CARD_OVERFLOW.key, fallback));
            }
        } catch (Throwable ignored) {
        }
        return StoredValues.fromStored(LiveCardOverflow.class, fallback);
    }

    public LiveCardScrollScope liveCardScrollScope() {
        String fallback = config == null ? "" : config.get(Settings.LIVE_CARD_SCROLL_SCOPE);
        try {
            SharedPreferences prefs = prefs();
            if (prefs != null && prefs.contains(Settings.LIVE_CARD_SCROLL_SCOPE.key)) {
                return StoredValues.fromStored(LiveCardScrollScope.class,
                        prefs.getString(Settings.LIVE_CARD_SCROLL_SCOPE.key, fallback));
            }
        } catch (Throwable ignored) {
        }
        return StoredValues.fromStored(LiveCardScrollScope.class, fallback);
    }

    public String defaultChineseMode(String configuredMode) {
        String configured = safe(configuredMode);
        ChineseMode mode = StoredValues.fromStored(ChineseMode.class, configured);
        if (mode == ChineseMode.OFF) return "";
        if (mode != ChineseMode.CYCLE) return LyricsDisplayMode.normalizeChineseMode(configured);
        String fallback = config == null
                ? Settings.LAST_CHINESE_CYCLE_MODE.defaultValue
                : config.get(Settings.LAST_CHINESE_CYCLE_MODE);
        try {
            SharedPreferences prefs = prefs();
            if (prefs != null && prefs.contains(Settings.LAST_CHINESE_CYCLE_MODE.key)) {
                return LyricsDisplayMode.normalizeChineseMode(
                        prefs.getString(Settings.LAST_CHINESE_CYCLE_MODE.key, fallback));
            }
        } catch (Throwable ignored) {
        }
        return LyricsDisplayMode.normalizeChineseMode(fallback);
    }

    /**
     * Korean reading mode actually in effect, honouring an in-progress cycle.
     *
     * <p>The fullscreen chip advances the cycle and writes the new mode to live preferences.
     * {@link SpotifyPlusConfig} is a snapshot and does not see that write, so anything that must
     * agree with what is on screen — including the session-owned Sound lane — has to read the
     * preference directly, exactly as {@link #defaultChineseMode} does.
     */
    public String currentKoreanMode(String configuredMode) {
        String configured = safe(configuredMode);
        if (!"cycle".equals(configured)) return KoreanDisplayMode.valueOfSetting(configured);
        String fallback = config == null
                ? Settings.LAST_KOREAN_CYCLE_MODE.defaultValue
                : config.get(Settings.LAST_KOREAN_CYCLE_MODE);
        try {
            SharedPreferences prefs = prefs();
            if (prefs != null && prefs.contains(Settings.LAST_KOREAN_CYCLE_MODE.key)) {
                return KoreanDisplayMode.valueOfSetting(
                        prefs.getString(Settings.LAST_KOREAN_CYCLE_MODE.key, fallback));
            }
        } catch (Throwable ignored) {
        }
        return KoreanDisplayMode.valueOfSetting(fallback);
    }

    /** Cyrillic reading mode actually in effect, honouring an in-progress cycle. */
    public String currentCyrillicMode(String configuredMode) {
        String configured = safe(configuredMode);
        if (StoredValues.fromStored(CyrillicMode.class, configured) != CyrillicMode.CYCLE) {
            return configured;
        }
        String fallback = config == null
                ? Settings.LAST_CYRILLIC_CYCLE_MODE.defaultValue
                : config.get(Settings.LAST_CYRILLIC_CYCLE_MODE);
        try {
            SharedPreferences prefs = prefs();
            if (prefs != null && prefs.contains(Settings.LAST_CYRILLIC_CYCLE_MODE.key)) {
                return safe(prefs.getString(Settings.LAST_CYRILLIC_CYCLE_MODE.key, fallback));
            }
        } catch (Throwable ignored) {
        }
        return safe(fallback);
    }

    /** "Spotlight" animation style = zoom + glow the active line/word, no gradient fill. */
    public boolean spotlightAnimation() {
        return animationStyle().isSpotlight();
    }

    /** "Apple Music" animation style = the Apple-owned motion/blur/fade stack (R3). */
    public boolean appleAnimation() {
        return animationStyle().isApple();
    }

    private AnimationStyle animationStyle() {
        return config == null ? StoredValues.defaultValue(AnimationStyle.class)
                : config.get(Settings.ANIMATION_STYLE, AnimationStyle.class);
    }

    public boolean lineSyncFillTopDown() {
        return lineSyncFillMode() == LineSyncFill.TOP_TO_BOTTOM;
    }

    public boolean lineSyncFillWord() {
        return false;
    }

    public boolean lineSyncFillSentence() {
        return lineSyncFillMode() == LineSyncFill.SENTENCE;
    }

    public LineSyncFill lineSyncFillMode() {
        return StoredValues.fromStored(LineSyncFill.class,
                config == null ? Settings.LINE_SYNC_FILL.defaultValue : config.get(Settings.LINE_SYNC_FILL));
    }

    public BackgroundQuality backgroundQuality() {
        return config == null ? BackgroundQuality.DEFAULT
                : config.get(Settings.BACKGROUND_QUALITY, BackgroundQuality.class);
    }

    public float lineBlurQualityMultiplier() {
        return backgroundQuality().blurMultiplier();
    }

    private SharedPreferences prefs() {
        return context == null ? null : context.getSharedPreferences(PREFS_MAIN, Context.MODE_PRIVATE);
    }

}
