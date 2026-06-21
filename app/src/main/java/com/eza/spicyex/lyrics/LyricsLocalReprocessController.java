package com.eza.spicyex.lyrics;

/** Serializes local romanization reprocess requests and coalesces one pending retry. */
public final class LyricsLocalReprocessController {
    private final LyricsSecondaryProcessor secondaryProcessor;
    private boolean processing;
    private boolean pending;

    public LyricsLocalReprocessController(LyricsSecondaryProcessor secondaryProcessor) {
        this.secondaryProcessor = secondaryProcessor;
    }

    public boolean isProcessing() {
        return processing;
    }

    public boolean request(
            LyricsDocument snapshot,
            boolean showRomanization,
            RomanizationOptions options,
            String reason,
            LyricsSecondaryProcessor.CurrentGuard currentGuard,
            Callback callback
    ) {
        if (snapshot == null || snapshot.lines == null || snapshot.lines.isEmpty()) return false;
        if (processing) {
            pending = true;
            return true;
        }
        processing = true;
        secondaryProcessor.reprocessLocal(snapshot, showRomanization, options, reason, currentGuard,
                (completedReason, changed, current) -> {
                    processing = false;
                    if (!current) return;
                    if (callback != null) callback.complete(completedReason, changed);
                    if (pending) {
                        pending = false;
                        if (callback != null) callback.repeat(completedReason + " pending");
                    }
                });
        return true;
    }

    public interface Callback {
        void complete(String reason, int changed);
        void repeat(String reason);
    }
}
