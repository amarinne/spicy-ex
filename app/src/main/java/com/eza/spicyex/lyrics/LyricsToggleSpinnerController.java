package com.eza.spicyex.lyrics;

import android.os.SystemClock;

/** Owns delayed pending-state rings for the romanization and translation toggle chips. */
public final class LyricsToggleSpinnerController {
    private static final long SHOW_DELAY_MS = 180L;

    private final ChipSpinnerDrawable romanSpinner;
    private final ChipSpinnerDrawable translationSpinner;
    private long romanPendingSinceMs;
    private long translationPendingSinceMs;

    public LyricsToggleSpinnerController(ChipSpinnerDrawable romanSpinner, ChipSpinnerDrawable translationSpinner) {
        this.romanSpinner = romanSpinner;
        this.translationSpinner = translationSpinner;
    }

    /** Plain (not AI) failures, each shown as a red "!" on its chip until a retry or success. */
    public void setFailed(boolean romanFailed, boolean translationFailed) {
        romanSpinner.setFailed(romanFailed);
        translationSpinner.setFailed(translationFailed);
    }

    public void update(boolean enabled, boolean romanPending, boolean translationPending) {
        update(enabled, romanPending, translationPending, false, false);
    }

    /**
     * @param romanFromAi       the reading on screen came from a model
     * @param translationFromAi the translation on screen came from a model
     */
    public void update(boolean enabled, boolean romanPending, boolean translationPending,
                       boolean romanFromAi, boolean translationFromAi) {
        update(enabled, romanPending, translationPending, romanFromAi, translationFromAi,
                false, false);
    }

    /**
     * AI work is immediate and visually distinct; ordinary processing keeps the delayed ring.
     * Precedence per chip: running AI, then failed AI (red), then accepted AI output (green).
     * A failure flag is only honored while no request is pending, so starting a retry clears
     * red into the running state without waiting for the next publication.
     */
    public void update(boolean enabled, boolean romanPending, boolean translationPending,
                       boolean romanFromAi, boolean translationFromAi,
                       boolean romanAiPending, boolean translationAiPending) {
        update(enabled, romanPending, translationPending, romanFromAi, translationFromAi,
                romanAiPending, translationAiPending, false, false);
    }

    public void update(boolean enabled, boolean romanPending, boolean translationPending,
                       boolean romanFromAi, boolean translationFromAi,
                       boolean romanAiPending, boolean translationAiPending,
                       boolean romanAiFailed, boolean translationAiFailed) {
        romanSpinner.setAiOutput(romanFromAi && !romanAiFailed);
        translationSpinner.setAiOutput(translationFromAi && !translationAiFailed);
        romanSpinner.setAiActive(romanAiPending);
        translationSpinner.setAiActive(translationAiPending);
        romanSpinner.setAiFailed(!romanAiPending && romanAiFailed);
        translationSpinner.setAiFailed(!translationAiPending && translationAiFailed);
        if (!enabled && !romanAiPending && !translationAiPending) {
            reset();
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (enabled && romanPending && !romanAiPending) {
            if (romanPendingSinceMs == 0L) romanPendingSinceMs = now;
        } else {
            romanPendingSinceMs = 0L;
        }
        if (enabled && translationPending && !translationAiPending) {
            if (translationPendingSinceMs == 0L) translationPendingSinceMs = now;
        } else {
            translationPendingSinceMs = 0L;
        }
        romanSpinner.setActive(romanAiPending
                || romanPendingSinceMs != 0L && now - romanPendingSinceMs >= SHOW_DELAY_MS);
        translationSpinner.setActive(translationAiPending
                || translationPendingSinceMs != 0L && now - translationPendingSinceMs >= SHOW_DELAY_MS);
    }

    /** Stops the rings. The AI mark is display state and is left alone. */
    public void reset() {
        romanPendingSinceMs = 0L;
        translationPendingSinceMs = 0L;
        romanSpinner.setAiActive(false);
        translationSpinner.setAiActive(false);
        romanSpinner.setActive(false);
        translationSpinner.setActive(false);
    }
}
