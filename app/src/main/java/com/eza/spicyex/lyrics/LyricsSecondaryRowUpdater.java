package com.eza.spicyex.lyrics;

import android.view.ViewGroup;

/**
 * Applies line-level secondary romanization/translation results to already parsed rows.
 */
public final class LyricsSecondaryRowUpdater {
    private final ViewGroup mountedRowsHost;
    private final LyricsLineViewState.Invalidation invalidation;

    public LyricsSecondaryRowUpdater(ViewGroup mountedRowsHost, LyricsLineViewState.Invalidation invalidation) {
        this.mountedRowsHost = mountedRowsHost;
        this.invalidation = invalidation;
    }

    public boolean refresh(LyricsDocument document, boolean showRomanization, boolean showTranslation, String japaneseReadingMode) {
        if (document == null || document.appliedLines == null || document.appliedLines.isEmpty()) {
            return false;
        }
        boolean structureChanged = false;
        for (AppliedLine row : document.appliedLines) {
            if (row == null || row.dotLine || row.bgLine || row.sourceLine == null) continue;
            String roman = safe(row.sourceLine.romanizedText);
            String translated = safe(row.sourceLine.translatedText);
            RefreshDecision decision = decideRefresh(row, roman, translated, showRomanization, showTranslation,
                    japaneseReadingMode, LyricsLineViewState.isMounted(row, mountedRowsHost));
            if (!decision.hasChanges()) continue;
            row.romanizedText = roman;
            row.translatedText = translated;
            row.japaneseReading = row.sourceLine.japaneseReading;
            if (decision.remountForFurigana) {
                LyricsLineViewState.clear(row, mountedRowsHost, invalidation);
                structureChanged = true;
                continue;
            }
            structureChanged |= LyricsLineViewState.applySecondaryTextUpdate(
                    row,
                    mountedRowsHost,
                    invalidation,
                    decision.romanChanged,
                    roman,
                    showRomanization,
                    decision.translatedChanged,
                    translated,
                    showTranslation);
        }
        return structureChanged;
    }

    public void clear(AppliedLine line) {
        LyricsLineViewState.clear(line, mountedRowsHost, invalidation);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    static RefreshDecision decideRefresh(
            AppliedLine row,
            String roman,
            String translated,
            boolean showRomanization,
            boolean showTranslation,
            String japaneseReadingMode,
            boolean isMounted
    ) {
        boolean romanChanged = row != null && !safeStatic(roman).equals(row.romanizedText);
        boolean translatedChanged = row != null && !safeStatic(translated).equals(row.translatedText);
        boolean japaneseReadingChanged = row != null
                && row.sourceLine != null
                && row.japaneseReading != row.sourceLine.japaneseReading;
        boolean furiganaAppeared = row != null
                && !hasFurigana(row.japaneseReading)
                && row.sourceLine != null
                && hasFurigana(row.sourceLine.japaneseReading);
        boolean remountForFurigana = furiganaAppeared
                && showRomanization
                && LyricsShellSettings.showJapaneseFurigana(japaneseReadingMode)
                && isMounted;
        return new RefreshDecision(romanChanged, translatedChanged, japaneseReadingChanged, remountForFurigana);
    }

    private static boolean hasFurigana(SpicyJapaneseChineseProcessor.JapaneseReading reading) {
        return reading != null && reading.furigana != null && !reading.furigana.isEmpty();
    }

    private static String safeStatic(String value) {
        return value == null ? "" : value;
    }

    static final class RefreshDecision {
        final boolean romanChanged;
        final boolean translatedChanged;
        final boolean japaneseReadingChanged;
        final boolean remountForFurigana;

        RefreshDecision(boolean romanChanged, boolean translatedChanged, boolean japaneseReadingChanged, boolean remountForFurigana) {
            this.romanChanged = romanChanged;
            this.translatedChanged = translatedChanged;
            this.japaneseReadingChanged = japaneseReadingChanged;
            this.remountForFurigana = remountForFurigana;
        }

        boolean hasChanges() {
            return romanChanged || translatedChanged || japaneseReadingChanged;
        }
    }
}
