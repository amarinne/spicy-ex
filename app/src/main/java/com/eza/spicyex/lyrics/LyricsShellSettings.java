package com.eza.spicyex.lyrics;

import android.content.Context;
import android.content.SharedPreferences;

import com.eza.spicyex.Settings;
import com.eza.spicyex.SettingsValueNormalizer;
import com.eza.spicyex.SpotifyPlusConfig;
import static com.eza.spicyex.lyrics.LyricUtils.safe;

/**
 * Owns normalized runtime reads for the fullscreen and now-playing lyrics surfaces.
 * Does not own schema/default declaration (Settings) or panel writes (SettingsStore).
 */
public final class LyricsShellSettings {
    private static final String PREFS_MAIN = "SpotifyPlus";

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

    public String lineSpacingMode() {
        String fallback = config == null ? "more" : config.get(Settings.LINE_SPACING);
        try {
            SharedPreferences prefs = prefs();
            if (prefs != null && prefs.contains(Settings.LINE_SPACING.key)) {
                return normalizeLineSpacingMode(prefs.getString(Settings.LINE_SPACING.key, fallback));
            }
        } catch (Throwable ignored) {
        }
        return normalizeLineSpacingMode(fallback);
    }

    public float lineSpacingMultiplier() {
        String spacing = lineSpacingMode();
        // Widened spread so the setting is clearly visible (previously 0.82–1.45 barely moved the
        // ~10/13dp row padding it scales). Tune freely.
        // Dialed back from the old 0.7–4.2 spread (too coarse a jump per step) to finer increments.
        switch (safe(spacing)) {
            case "compact": return 0.8f;
            case "spacious": return 1.45f;
            case "more": return 1.9f;
            case "max": return 2.5f;
            default: return 1.1f;
        }
    }

    public String lyricWeight() {
        String fallback = SettingsValueNormalizer.normalizeTextWeight(config == null ? "" : config.get(Settings.LYRICS_WEIGHT));
        return readWeight(Settings.LYRICS_WEIGHT, fallback);
    }

    public String liveCardWeight() {
        String fallback = SettingsValueNormalizer.normalizeTextWeight(config == null ? "" : config.get(Settings.LIVE_CARD_WEIGHT));
        return readWeight(Settings.LIVE_CARD_WEIGHT, fallback);
    }

    private String readWeight(Settings.Setting<String> setting, String fallback) {
        try {
            SharedPreferences prefs = prefs();
            if (prefs != null && prefs.contains(setting.key)) {
                return SettingsValueNormalizer.normalizeTextWeight(prefs.getString(setting.key, fallback));
            }
        } catch (Throwable ignored) {
        }
        return fallback;
    }

    public String lyricsTextSizeMode() {
        String fallback = SettingsValueNormalizer.normalizeTextSizeMode(config == null ? "" : config.get(Settings.LYRICS_TEXT_SIZE));
        try {
            SharedPreferences prefs = prefs();
            if (prefs != null && prefs.contains(Settings.LYRICS_TEXT_SIZE.key)) {
                return SettingsValueNormalizer.normalizeTextSizeMode(prefs.getString(Settings.LYRICS_TEXT_SIZE.key, fallback));
            }
        } catch (Throwable ignored) {
        }
        return fallback;
    }

    public float lyricsTextSizeMultiplier() {
        return SettingsValueNormalizer.textSizeMultiplierFor(lyricsTextSizeMode());
    }

    /** Independent size mode for the now-playing live card (Settings.LIVE_CARD_TEXT_SIZE). */
    public String liveCardTextSizeMode() {
        String fallback = SettingsValueNormalizer.normalizeTextSizeMode(config == null ? "" : config.get(Settings.LIVE_CARD_TEXT_SIZE));
        try {
            SharedPreferences prefs = prefs();
            if (prefs != null && prefs.contains(Settings.LIVE_CARD_TEXT_SIZE.key)) {
                return SettingsValueNormalizer.normalizeTextSizeMode(prefs.getString(Settings.LIVE_CARD_TEXT_SIZE.key, fallback));
            }
        } catch (Throwable ignored) {
        }
        return fallback;
    }

    public float liveCardTextSizeMultiplier() {
        return SettingsValueNormalizer.textSizeMultiplierFor(liveCardTextSizeMode());
    }

    public String liveCardSecondaryMode() {
        String fallback = config == null ? Settings.LIVE_CARD_SECONDARY_MODE.defaultValue
                : normalizeLiveCardSecondaryMode(config.get(Settings.LIVE_CARD_SECONDARY_MODE));
        try {
            SharedPreferences prefs = prefs();
            if (prefs != null && prefs.contains(Settings.LIVE_CARD_SECONDARY_MODE.key)) {
                return normalizeLiveCardSecondaryMode(prefs.getString(Settings.LIVE_CARD_SECONDARY_MODE.key, fallback));
            }
            if (prefs != null && prefs.contains(Settings.LIVE_CARD_SHOW_TRANSLITERATION.key)
                    && prefs.getBoolean(Settings.LIVE_CARD_SHOW_TRANSLITERATION.key, false)) {
                return "Transliteration";
            }
        } catch (Throwable ignored) {
        }
        return fallback;
    }

    public boolean liveCardShowTransliteration() {
        String mode = liveCardSecondaryMode();
        return "Transliteration".equals(mode) || "Both".equals(mode);
    }

    public boolean liveCardShowTranslation() {
        String mode = liveCardSecondaryMode();
        return "Translation".equals(mode) || "Both".equals(mode);
    }

    public String liveCardAnimationMode() {
        String fallback = config == null ? "" : config.get(Settings.LIVE_CARD_ANIMATION);
        try {
            SharedPreferences prefs = prefs();
            if (prefs != null && prefs.contains(Settings.LIVE_CARD_ANIMATION.key)) {
                return normalizeLiveCardAnimationMode(prefs.getString(Settings.LIVE_CARD_ANIMATION.key, fallback));
            }
        } catch (Throwable ignored) {
        }
        return normalizeLiveCardAnimationMode(fallback);
    }

    public String liveCardGlowMode() {
        String fallback = config == null ? "" : config.get(Settings.LIVE_CARD_GLOW);
        try {
            SharedPreferences prefs = prefs();
            if (prefs != null && prefs.contains(Settings.LIVE_CARD_GLOW.key)) {
                return normalizeLiveCardGlowMode(prefs.getString(Settings.LIVE_CARD_GLOW.key, fallback));
            }
        } catch (Throwable ignored) {
        }
        return normalizeLiveCardGlowMode(fallback);
    }

    public String liveCardLineSyncFillMode() {
        String fallback = config == null ? "" : config.get(Settings.LIVE_CARD_LINE_SYNC_FILL);
        try {
            SharedPreferences prefs = prefs();
            if (prefs != null && prefs.contains(Settings.LIVE_CARD_LINE_SYNC_FILL.key)) {
                return normalizeLiveCardLineSyncFillMode(prefs.getString(Settings.LIVE_CARD_LINE_SYNC_FILL.key, fallback));
            }
        } catch (Throwable ignored) {
        }
        return normalizeLiveCardLineSyncFillMode(fallback);
    }

    public String liveCardTransitionMode() {
        String fallback = config == null ? "" : config.get(Settings.LIVE_CARD_TRANSITION);
        try {
            SharedPreferences prefs = prefs();
            if (prefs != null && prefs.contains(Settings.LIVE_CARD_TRANSITION.key)) {
                return normalizeLiveCardTransitionMode(prefs.getString(Settings.LIVE_CARD_TRANSITION.key, fallback));
            }
        } catch (Throwable ignored) {
        }
        return normalizeLiveCardTransitionMode(fallback);
    }

    public String liveCardOverflowMode() {
        String fallback = config == null ? "" : config.get(Settings.LIVE_CARD_OVERFLOW);
        try {
            SharedPreferences prefs = prefs();
            if (prefs != null && prefs.contains(Settings.LIVE_CARD_OVERFLOW.key)) {
                return normalizeLiveCardOverflowMode(prefs.getString(Settings.LIVE_CARD_OVERFLOW.key, fallback));
            }
        } catch (Throwable ignored) {
        }
        return normalizeLiveCardOverflowMode(fallback);
    }

    public String liveCardScrollScope() {
        String fallback = config == null ? "" : config.get(Settings.LIVE_CARD_SCROLL_SCOPE);
        try {
            SharedPreferences prefs = prefs();
            if (prefs != null && prefs.contains(Settings.LIVE_CARD_SCROLL_SCOPE.key)) {
                return normalizeLiveCardScrollScope(prefs.getString(Settings.LIVE_CARD_SCROLL_SCOPE.key, fallback));
            }
        } catch (Throwable ignored) {
        }
        return normalizeLiveCardScrollScope(fallback);
    }

    public String defaultChineseMode(String configuredMode) {
        String configured = safe(configuredMode);
        if ("off".equals(configured)) return "";
        if (!"cycle".equals(configured)) return normalizeChineseMode(configured);
        String fallback = config == null
                ? Settings.LAST_CHINESE_CYCLE_MODE.defaultValue
                : config.get(Settings.LAST_CHINESE_CYCLE_MODE);
        try {
            SharedPreferences prefs = prefs();
            if (prefs != null && prefs.contains(Settings.LAST_CHINESE_CYCLE_MODE.key)) {
                return normalizeChineseMode(prefs.getString(Settings.LAST_CHINESE_CYCLE_MODE.key, fallback));
            }
        } catch (Throwable ignored) {
        }
        return normalizeChineseMode(fallback);
    }

    /** "Spotlight" animation style = zoom + glow the active line/word, no gradient fill. */
    public boolean spotlightAnimation() {
        String fallback = config == null ? "" : config.get(Settings.ANIMATION_STYLE);
        try {
            SharedPreferences prefs = prefs();
            if (prefs != null && prefs.contains(Settings.ANIMATION_STYLE.key)) {
                return "Spotlight".equals(prefs.getString(Settings.ANIMATION_STYLE.key, fallback));
            }
        } catch (Throwable ignored) {
        }
        return "Spotlight".equals(fallback);
    }

    public boolean lineSyncFillTopDown() {
        return "Top to bottom".equals(lineSyncFillMode());
    }

    public boolean lineSyncFillWord() {
        return false;
    }

    public boolean lineSyncFillSentence() {
        return "Left to right (sentence)".equals(lineSyncFillMode());
    }

    public String lineSyncFillMode() {
        String fallback = normalizeLineSyncFillMode(config == null ? "" : config.get(Settings.LINE_SYNC_FILL));
        try {
            SharedPreferences prefs = prefs();
            if (prefs != null && prefs.contains(Settings.LINE_SYNC_FILL.key)) {
                return normalizeLineSyncFillMode(prefs.getString(Settings.LINE_SYNC_FILL.key, fallback));
            }
        } catch (Throwable ignored) {
        }
        return fallback;
    }

    public float lineBlurQualityMultiplier() {
        String quality = config == null ? "" : config.get(Settings.BACKGROUND_QUALITY);
        if ("superLow".equalsIgnoreCase(quality)) return 0f;
        if ("low".equalsIgnoreCase(quality)) return 0.35f;
        if ("mid".equalsIgnoreCase(quality)) return 0.70f;
        return 1f;
    }

    public static String normalizeChineseMode(String mode) {
        if ("jyutping".equalsIgnoreCase(mode) || "cantonese".equalsIgnoreCase(mode)) return SpotifyPlusConfig.CHINESE_MODE_JYUTPING;
        return SpotifyPlusConfig.CHINESE_MODE_PINYIN;
    }

    public static boolean showJapaneseFurigana(String japaneseReadingMode) {
        return SpotifyPlusConfig.JP_READING_FURIGANA_ONLY.equals(japaneseReadingMode)
                || SpotifyPlusConfig.JP_READING_FURIGANA_ROMAJI.equals(japaneseReadingMode);
    }

    public static boolean showJapaneseRomaji(String japaneseReadingMode) {
        return SpotifyPlusConfig.JP_READING_ROMAJI_ONLY.equals(japaneseReadingMode)
                || SpotifyPlusConfig.JP_READING_FURIGANA_ROMAJI.equals(japaneseReadingMode);
    }

    private static String normalizeLineSpacingMode(String mode) {
        String value = safe(mode);
        switch (value) {
            case "compact":
            case "default":
            case "spacious":
            case "more":
            case "max":
                return value;
            default:
                return "spacious";
        }
    }

    private static String normalizeLineSyncFillMode(String mode) {
        String value = safe(mode);
        if ("Left to right".equals(value)) return "Left to right (block)";
        if ("Left to right (word)".equals(value)) return "Left to right (sentence)";
        if ("Left to right (block)".equals(value)
                || "Left to right (sentence)".equals(value)) return value;
        return "Top to bottom";
    }

    private static String normalizeLiveCardAnimationMode(String mode) {
        String value = safe(mode);
        if ("Full".equals(value)) return "Spotlight word";
        if ("Minimal".equals(value) || "Karaoke fill".equals(value) || "Spotlight word".equals(value)) return value;
        return "Karaoke fill";
    }

    private static String normalizeLiveCardSecondaryMode(String mode) {
        String value = safe(mode);
        if ("Transliteration".equals(value) || "Translation".equals(value) || "Both".equals(value)) return value;
        if ("Romanization".equals(value) || "Romanized".equals(value)) return "Transliteration";
        return "Main only";
    }

    private static String normalizeLiveCardGlowMode(String mode) {
        String value = safe(mode);
        if ("Off".equals(value) || "Word only".equals(value) || "Subtle line".equals(value)) return value;
        return "Off";
    }

    private static String normalizeLiveCardLineSyncFillMode(String mode) {
        String value = safe(mode);
        if ("Left to right (sentence)".equals(value)) return value;
        return "Top to bottom";
    }

    private static String normalizeLiveCardTransitionMode(String mode) {
        String value = safe(mode);
        if ("Crossfade".equals(value) || "None".equals(value)) return value;
        return "Fade up";
    }

    private static String normalizeLiveCardOverflowMode(String mode) {
        String value = safe(mode);
        if ("Wrap".equals(value) || "Clip".equals(value) || "Scroll with lyric".equals(value)) return value;
        return "Wrap";
    }

    private static String normalizeLiveCardScrollScope(String scope) {
        String value = safe(scope);
        if ("Individual lines".equals(value)) return value;
        return "Grouped";
    }

    private SharedPreferences prefs() {
        return context == null ? null : context.getSharedPreferences(PREFS_MAIN, Context.MODE_PRIVATE);
    }

}
