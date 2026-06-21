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

    public boolean refresh(LyricsDocument document, boolean showRomanization, boolean showTranslation) {
        if (document == null || document.appliedLines == null || document.appliedLines.isEmpty()) {
            return false;
        }
        boolean structureChanged = false;
        for (AppliedLine row : document.appliedLines) {
            if (row == null || row.dotLine || row.bgLine || row.sourceLine == null) continue;
            String roman = safe(row.sourceLine.romanizedText);
            String translated = safe(row.sourceLine.translatedText);
            boolean romanChanged = !roman.equals(row.romanizedText);
            boolean translatedChanged = !translated.equals(row.translatedText);
            if (!romanChanged && !translatedChanged) continue;
            row.romanizedText = roman;
            row.translatedText = translated;
            structureChanged |= LyricsLineViewState.applySecondaryTextUpdate(
                    row,
                    mountedRowsHost,
                    invalidation,
                    romanChanged,
                    roman,
                    showRomanization,
                    translatedChanged,
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
}
