package com.eza.spicyex.lyrics;

import com.eza.spicyex.SpotifyPlusConfig;
import com.eza.spicyex.lyrics.language.ReadingLanguagePolicy;
import com.eza.spicyex.lyrics.language.SpicyTextDetection;

/** Shared display-mode decisions for fullscreen rows and the now-playing lyric card. */
public final class LyricsDisplayMode {
    private LyricsDisplayMode() {
    }

    public static boolean isJapaneseLine(AppliedLine line) {
        com.eza.spicyex.lyrics.session.DetectionResult detection = ReadingLanguagePolicy.detectionFor(line);
        if (detection != null) {
            return detection.hasLanguage() && "ja".equals(detection.language);
        }
        return line != null
                && !isChineseModeLine(line)
                && ((!line.bgLine && line.japaneseReading != null && line.japaneseReading.furigana != null
                && !line.japaneseReading.furigana.isEmpty())
                || SpicyTextDetection.hasKana(line.text));
    }

    public static boolean isChineseModeLine(AppliedLine line) {
        return line != null && line.sourceLine != null && !isBlank(line.sourceLine.chineseMode);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isEmpty();
    }

    public static boolean showJapaneseFurigana(AppliedLine line, boolean showRomanization, String japaneseReadingMode) {
        return isJapaneseLine(line)
                && showRomanization
                && showJapaneseFurigana(japaneseReadingMode);
    }

    public static boolean showJapaneseRomaji(AppliedLine line, boolean showRomanization, String japaneseReadingMode) {
        return isJapaneseLine(line)
                && showRomanization
                && showJapaneseRomaji(japaneseReadingMode);
    }

    /** One stored Chinese mode, canonicalized to the value the romanizers compare against. */
    public static String normalizeChineseMode(String mode) {
        if (mode != null && (mode.toLowerCase(java.util.Locale.ROOT).endsWith("-eza")
                || mode.toLowerCase(java.util.Locale.ROOT).endsWith("_eza"))) {
            String base = mode.substring(0, mode.length() - 4);
            if ("jyutping".equalsIgnoreCase(base) || "cantonese".equalsIgnoreCase(base)) {
                return SpotifyPlusConfig.CHINESE_MODE_JYUTPING + "-eza";
            }
            return SpotifyPlusConfig.CHINESE_MODE_PINYIN + "-eza";
        }
        if ("jyutping".equalsIgnoreCase(mode) || "cantonese".equalsIgnoreCase(mode)) {
            return SpotifyPlusConfig.CHINESE_MODE_JYUTPING;
        }
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
}
