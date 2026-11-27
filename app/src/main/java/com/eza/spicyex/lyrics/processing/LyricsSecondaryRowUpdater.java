package com.eza.spicyex.lyrics.processing;

import com.eza.spicyex.lyrics.language.SpicyJapaneseChineseProcessor;

import android.view.ViewGroup;
import com.eza.spicyex.lyrics.AppliedLine;
import com.eza.spicyex.lyrics.LyricUtils;
import com.eza.spicyex.lyrics.LyricsDisplayMode;
import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.lyrics.LyricsLineViewState;
import com.eza.spicyex.lyrics.SecondaryDisplayText;

/**
 * Applies line-level secondary romanization/translation results to already parsed rows.
 */
public final class LyricsSecondaryRowUpdater {
    private final ViewGroup mountedRowsHost;
    private final LyricsLineViewState.Invalidation invalidation;
    /** Set by the translation button; the next refresh re-syncs rows to the toggle. */
    private boolean translationToggled;

    public void markTranslationToggled() {
        translationToggled = true;
    }

    public LyricsSecondaryRowUpdater(ViewGroup mountedRowsHost, LyricsLineViewState.Invalidation invalidation) {
        this.mountedRowsHost = mountedRowsHost;
        this.invalidation = invalidation;
    }

    public boolean refresh(LyricsDocument document, boolean showRomanization, boolean showTranslation, String japaneseReadingMode) {
        return refresh(document, showRomanization, showTranslation, japaneseReadingMode, true);
    }

    public boolean refresh(LyricsDocument document, boolean showRomanization, boolean showTranslation,
                           String japaneseReadingMode, boolean translationBright) {
        if (document == null || document.appliedLines == null || document.appliedLines.isEmpty()) {
            return false;
        }
        boolean structureChanged = false;
        // Only a press of the translation button remounts rows for it (told explicitly - guessing
        // from the previous refresh missed the first press after a cached load). Checked on every
        // refresh instead, any row that looked out of sync was remounted again and again.
        boolean translationToggled = this.translationToggled;
        this.translationToggled = false;
        for (AppliedLine row : document.appliedLines) {
            if (row == null || row.dotLine || row.bgLine || row.sourceLine == null) continue;
            String roman = safe(row.sourceLine.romanizedText);
            String translated = safe(row.sourceLine.translatedText);
            boolean aiPending = row.sourceLine.translationAiPending;
            boolean pendingChanged = row.translationAiPending != aiPending;
            boolean mounted = LyricsLineViewState.isMounted(row, mountedRowsHost);
            RefreshDecision decision = decideRefresh(row, roman, translated, showRomanization, showTranslation,
                    japaneseReadingMode, mounted);
            // Toggling translations with unchanged text used to change nothing here: the faded-out
            // views stayed in the row (the gap left behind) and, faded, never came back when the
            // toggle was turned on again. A mounted row whose translation presence no longer
            // matches the toggle is remounted; the caller's reflow springs the rows to close or
            // open the space.
            String existingReading = SecondaryDisplayText.visibleReading(row, showRomanization, japaneseReadingMode);
            boolean wantsTranslation = showTranslation && !row.bgLine
                    && !LyricUtils.isBlank(SecondaryDisplayText.translation(row, existingReading));
            boolean hasTranslation = LyricsLineViewState.translationView(row) != null;
            if (translationToggled && mounted && !decision.hasChanges() && wantsTranslation != hasTranslation) {
                row.translationAiPending = aiPending;
                LyricsLineViewState.clear(row, mountedRowsHost, invalidation);
                structureChanged = true;
                continue;
            }
            if (!decision.hasChanges() && !pendingChanged) continue;
            boolean wasPending = row.translationAiPending;
            boolean aiArrived = document.translationFromAi && !aiPending
                    && (wasPending || (decision.translatedChanged && !LyricUtils.isBlank(translated)));
            row.romanizedText = roman;
            row.translatedText = translated;
            row.translationAiPending = aiPending;
            row.japaneseReading = row.sourceLine.japaneseReading;
            row.readingRenderPlan = row.sourceLine.readingRenderPlan;
            if (decision.remountForFurigana || decision.remountForReadingPlan) {
                LyricsLineViewState.clear(row, mountedRowsHost, invalidation);
                structureChanged = true;
                continue;
            }
            String displayReading = SecondaryDisplayText.visibleReading(row, showRomanization, japaneseReadingMode);
            String displayTranslation = SecondaryDisplayText.translation(row, displayReading);
            boolean rowStructureChanged = decision.hasChanges() && LyricsLineViewState.applySecondaryTextUpdate(
                    row,
                    mountedRowsHost,
                    invalidation,
                    decision.romanChanged,
                    displayReading,
                    showRomanization,
                    decision.translatedChanged,
                    displayTranslation,
                    showTranslation);
            structureChanged |= rowStructureChanged;
            if (!rowStructureChanged && mounted) {
                android.view.View tv = LyricsLineViewState.translationView(row);
                if (tv instanceof com.eza.spicyex.lyrics.SpicyAnimatedTextView) {
                    com.eza.spicyex.lyrics.SpicyAnimatedTextView animated =
                            (com.eza.spicyex.lyrics.SpicyAnimatedTextView) tv;
                    if (pendingChanged || decision.translatedChanged) {
                        animated.setBrightnessMultiplier(
                                com.eza.spicyex.lyrics.LyricsRowViewFactory.translationBrightness(
                                        translationBright, aiPending));
                    }
                    if (aiArrived) animated.animateTranslationArrival();
                }
            }
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
        boolean readingPlanChanged = row != null && row.sourceLine != null
                && row.readingRenderPlan != row.sourceLine.readingRenderPlan;
        boolean furiganaAppeared = row != null
                && !hasFurigana(row.japaneseReading)
                && row.sourceLine != null
                && hasFurigana(row.sourceLine.japaneseReading);
        boolean remountForFurigana = furiganaAppeared
                && showRomanization
                && LyricsDisplayMode.showJapaneseFurigana(japaneseReadingMode)
                && isMounted;
        boolean remountForReadingPlan = readingPlanChanged && showRomanization && isMounted;
        return new RefreshDecision(romanChanged, translatedChanged, japaneseReadingChanged,
                readingPlanChanged, remountForFurigana, remountForReadingPlan);
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
        final boolean readingPlanChanged;
        final boolean remountForFurigana;
        final boolean remountForReadingPlan;

        RefreshDecision(boolean romanChanged, boolean translatedChanged, boolean japaneseReadingChanged,
                        boolean readingPlanChanged, boolean remountForFurigana, boolean remountForReadingPlan) {
            this.romanChanged = romanChanged;
            this.translatedChanged = translatedChanged;
            this.japaneseReadingChanged = japaneseReadingChanged;
            this.readingPlanChanged = readingPlanChanged;
            this.remountForFurigana = remountForFurigana;
            this.remountForReadingPlan = remountForReadingPlan;
        }

        boolean hasChanges() {
            return romanChanged || translatedChanged || japaneseReadingChanged || readingPlanChanged;
        }
    }
}
