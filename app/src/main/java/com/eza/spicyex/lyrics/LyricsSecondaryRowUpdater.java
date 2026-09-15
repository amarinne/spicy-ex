package com.eza.spicyex.lyrics;

import android.view.ViewGroup;

/**
 * Applies line-level secondary romanization/translation results to already parsed rows.
 */
public final class LyricsSecondaryRowUpdater {
    private final ViewGroup mountedRowsHost;
    private final LyricsLineViewState.Invalidation invalidation;
    private final TranslationAppender translationAppender;
    private final RomanAppender romanAppender;
    private final ViewOp translationRemover;
    private final ViewOp romanRemover;

    public LyricsSecondaryRowUpdater(ViewGroup mountedRowsHost, LyricsLineViewState.Invalidation invalidation) {
        this(mountedRowsHost, invalidation, null, null, null, null);
    }

    public LyricsSecondaryRowUpdater(ViewGroup mountedRowsHost, LyricsLineViewState.Invalidation invalidation,
                                      TranslationAppender translationAppender) {
        this(mountedRowsHost, invalidation, translationAppender, null, null, null);
    }

    public LyricsSecondaryRowUpdater(ViewGroup mountedRowsHost, LyricsLineViewState.Invalidation invalidation,
                                      TranslationAppender translationAppender, RomanAppender romanAppender) {
        this(mountedRowsHost, invalidation, translationAppender, romanAppender, null, null);
    }

    public LyricsSecondaryRowUpdater(ViewGroup mountedRowsHost, LyricsLineViewState.Invalidation invalidation,
                                      TranslationAppender translationAppender, RomanAppender romanAppender,
                                      ViewOp translationRemover, ViewOp romanRemover) {
        this.mountedRowsHost = mountedRowsHost;
        this.invalidation = invalidation;
        this.translationAppender = translationAppender;
        this.romanAppender = romanAppender;
        this.translationRemover = translationRemover;
        this.romanRemover = romanRemover;
    }

    /**
     * Handles a pure visibility toggle (romanToggle/translationToggle taps): the text itself did
     * not change, only whether it should be shown, which {@link #refresh} never triggers on since
     * it is gated on the text value changing. Grows/shrinks each already-mounted row in place via
     * the same appenders {@link #refresh} uses, instead of the caller falling back to a full
     * document rerender just because no row's underlying text changed. Lines needing per-word
     * restructuring (furigana, aligned/timed romanization) fall back to a per-line remount, which
     * is still far cheaper than remounting the whole document.
     */
    public boolean applyVisibilityToggle(LyricsDocument document, boolean showRomanization, boolean showTranslation) {
        if (document == null || document.appliedLines == null || document.appliedLines.isEmpty()) return false;
        boolean structureChanged = false;
        for (AppliedLine row : document.appliedLines) {
            if (row == null || row.dotLine || row.bgLine) continue;
            if (!LyricsLineViewState.isMounted(row, mountedRowsHost)) continue;
            // refresh() copies row.sourceLine's text into row.romanizedText/translatedText before
            // deciding anything; this path skipped that (nothing about the text changed, only
            // visibility), so row's cached copy could still be blank the first time visibility
            // ever turns on for a row - toggleOne would then never see anything to add.
            if (row.sourceLine != null) {
                row.romanizedText = safe(row.sourceLine.romanizedText);
                row.translatedText = safe(row.sourceLine.translatedText);
            }
            structureChanged |= toggleOne(row, LyricsLineViewState.romanView(row) != null,
                    showRomanization && !isBlank(row.romanizedText), romanAppender == null ? null : romanAppender::append,
                    romanRemover);
            structureChanged |= toggleOne(row, LyricsLineViewState.translationView(row) != null,
                    showTranslation && !isBlank(row.translatedText), translationAppender == null ? null : translationAppender::append,
                    translationRemover);
        }
        return structureChanged;
    }

    private boolean toggleOne(AppliedLine row, boolean has, boolean want, ViewOp appender, ViewOp remover) {
        if (want && !has) {
            if (appender == null || !appender.apply(row)) {
                LyricsLineViewState.clear(row, mountedRowsHost, invalidation);
            }
            return true;
        }
        if (!want && has) {
            if (remover == null || !remover.apply(row)) {
                LyricsLineViewState.clear(row, mountedRowsHost, invalidation);
            }
            return true;
        }
        return false;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
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
            row.readingRenderPlan = row.sourceLine.readingRenderPlan;
            if (decision.remountForFurigana || decision.remountForReadingPlan) {
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
                    showTranslation,
                    translationAppender,
                    romanAppender);
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
                && LyricsShellSettings.showJapaneseFurigana(japaneseReadingMode)
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

    /** Builds and appends a translation view onto an already-mounted row in place. */
    public interface TranslationAppender {
        boolean append(AppliedLine line);
    }

    /** Builds and appends a romanization view onto an already-mounted row in place, for lines
     *  simple enough that it can be (see {@link LyricsRowViewFactory#appendRomanView}). */
    public interface RomanAppender {
        boolean append(AppliedLine line);
    }

    /** Generic add/remove op on a mounted row - used for the remover side of a toggle, where
     *  either translation or romanization can be the target. */
    public interface ViewOp {
        boolean apply(AppliedLine line);
    }
}
