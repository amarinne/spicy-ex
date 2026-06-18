package com.eza.spicyex.lyrics;

import android.content.Context;
import android.content.SharedPreferences;

import com.eza.spicyex.Settings;
import com.eza.spicyex.SpotifyPlusConfig;
import static com.eza.spicyex.lyrics.LyricUtils.safe;

/** Settings access and normalization for the native lyrics shell. */
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
        String fallback = normalizeWeight(config == null ? "" : config.get(Settings.LYRICS_WEIGHT));
        try {
            SharedPreferences prefs = prefs();
            if (prefs != null && prefs.contains(Settings.LYRICS_WEIGHT.key)) {
                return normalizeWeight(prefs.getString(Settings.LYRICS_WEIGHT.key, fallback));
            }
        } catch (Throwable ignored) {
        }
        return fallback;
    }

    private static String normalizeWeight(String w) {
        if ("Regular".equals(w) || "Bold".equals(w)) return w;
        return "Medium";
    }

    public String lyricsTextSizeMode() {
        String fallback = normalizeTextSizeMode(config == null ? "" : config.get(Settings.LYRICS_TEXT_SIZE));
        try {
            SharedPreferences prefs = prefs();
            if (prefs != null && prefs.contains(Settings.LYRICS_TEXT_SIZE.key)) {
                return normalizeTextSizeMode(prefs.getString(Settings.LYRICS_TEXT_SIZE.key, fallback));
            }
        } catch (Throwable ignored) {
        }
        return fallback;
    }

    public float lyricsTextSizeMultiplier() {
        return textSizeMultiplierFor(lyricsTextSizeMode());
    }

    /** Independent size mode for the now-playing live card (Settings.LIVE_CARD_TEXT_SIZE). */
    public String liveCardTextSizeMode() {
        String fallback = normalizeTextSizeMode(config == null ? "" : config.get(Settings.LIVE_CARD_TEXT_SIZE));
        try {
            SharedPreferences prefs = prefs();
            if (prefs != null && prefs.contains(Settings.LIVE_CARD_TEXT_SIZE.key)) {
                return normalizeTextSizeMode(prefs.getString(Settings.LIVE_CARD_TEXT_SIZE.key, fallback));
            }
        } catch (Throwable ignored) {
        }
        return fallback;
    }

    public float liveCardTextSizeMultiplier() {
        return textSizeMultiplierFor(liveCardTextSizeMode());
    }

    private static float textSizeMultiplierFor(String mode) {
        // Dialed back from 0.78/1.45/1.9 — the steps jumped too far for fine tuning.
        switch (mode) {
            case "small": return 0.88f;
            case "large": return 1.2f;
            case "xlarge": return 1.45f;
            default: return 1.0f;
        }
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
                return "more";
        }
    }

    private static String normalizeTextSizeMode(String mode) {
        String value = safe(mode);
        switch (value) {
            case "small":
            case "normal":
            case "large":
            case "xlarge":
                return value;
            default:
                return "normal";
        }
    }

    private static String normalizeLineSyncFillMode(String mode) {
        String value = safe(mode);
        if ("Left to right".equals(value)) return "Left to right";
        return "Top to bottom";
    }

    private SharedPreferences prefs() {
        return context == null ? null : context.getSharedPreferences(PREFS_MAIN, Context.MODE_PRIVATE);
    }

}
