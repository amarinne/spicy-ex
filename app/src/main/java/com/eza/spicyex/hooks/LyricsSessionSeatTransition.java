package com.eza.spicyex.hooks;

/** Pure transition rule for a settings-driven catalog re-seat. */
final class LyricsSessionSeatTransition {
    enum Action {
        KEEP_EMPTY,
        PUBLISH_SEAT,
        RETIRE_LOADING,
        RETIRE_NO_LYRICS
    }

    private LyricsSessionSeatTransition() {}

    static Action reconcile(boolean displayedBasePresent, boolean eligibleSeatPresent,
                            boolean acquisitionPlanned) {
        if (eligibleSeatPresent) return Action.PUBLISH_SEAT;
        if (!displayedBasePresent) return Action.KEEP_EMPTY;
        return acquisitionPlanned ? Action.RETIRE_LOADING : Action.RETIRE_NO_LYRICS;
    }

    static boolean fetchFailureNeedsNoLyrics(boolean displayedBasePresent) {
        return !displayedBasePresent;
    }
}
