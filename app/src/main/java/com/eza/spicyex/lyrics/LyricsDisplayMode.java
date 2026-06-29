package com.eza.spicyex.lyrics;

/** Shared display-mode decisions for fullscreen rows and the now-playing lyric card. */
public final class LyricsDisplayMode {
    private LyricsDisplayMode() {
    }

    public static boolean isJapaneseLine(AppliedLine line) {
        return line != null
                && !isChineseModeLine(line)
                && ((line.japaneseReading != null && line.japaneseReading.furigana != null
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
                && LyricsShellSettings.showJapaneseFurigana(japaneseReadingMode);
    }

    public static boolean showJapaneseRomaji(AppliedLine line, boolean showRomanization, String japaneseReadingMode) {
        return isJapaneseLine(line)
                && showRomanization
                && LyricsShellSettings.showJapaneseRomaji(japaneseReadingMode);
    }
}
