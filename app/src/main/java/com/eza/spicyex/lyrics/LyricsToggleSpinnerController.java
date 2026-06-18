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

    public void update(boolean enabled, boolean romanPending, boolean translationPending) {
        if (!enabled) {
            reset();
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (romanPending) {
            if (romanPendingSinceMs == 0L) romanPendingSinceMs = now;
        } else {
            romanPendingSinceMs = 0L;
        }
        if (translationPending) {
            if (translationPendingSinceMs == 0L) translationPendingSinceMs = now;
        } else {
            translationPendingSinceMs = 0L;
        }
        romanSpinner.setActive(romanPendingSinceMs != 0L && now - romanPendingSinceMs >= SHOW_DELAY_MS);
        translationSpinner.setActive(translationPendingSinceMs != 0L && now - translationPendingSinceMs >= SHOW_DELAY_MS);
    }

    public void reset() {
        romanPendingSinceMs = 0L;
        translationPendingSinceMs = 0L;
        romanSpinner.setActive(false);
        translationSpinner.setActive(false);
    }
}
