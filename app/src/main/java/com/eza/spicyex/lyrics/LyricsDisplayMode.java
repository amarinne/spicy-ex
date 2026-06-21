package com.eza.spicyex.lyrics;

/** Shared display-mode decisions for fullscreen rows and the now-playing lyric card. */
public final class LyricsDisplayMode {
    private LyricsDisplayMode() {
    }

    public static boolean isJapaneseLine(AppliedLine line) {
        return line != null
                && ((line.japaneseReading != null && line.japaneseReading.furigana != null
                && !line.japaneseReading.furigana.isEmpty())
                || SpicyJapaneseChineseProcessor.canRomanizeJapanese(line.text));
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
