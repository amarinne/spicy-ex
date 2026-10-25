package com.eza.spicyex.hooks;

import com.eza.spicyex.xposed.XpLog;

/**
 * Failure handling for surface document preparation that ran on the lyrics IO worker.
 *
 * <p>The log line names the generation captured when the candidate was offered. Reading
 * {@code NativeSpicyLyricsHook.fetchGeneration} at failure time would report a newer run instead.
 * The main-thread fallback only acts on the candidate the gate still accepts, never adopts the
 * failed document, and keeps lyrics already displayed for the current track.
 */
final class LyricsSurfacePrepareFailure {
    /** State the fallback reads and the actions it may take on the mounted surface. */
    interface Surface {
        boolean isRunning();

        String currentTrackId();

        String displayedTrackId();

        boolean hasDisplayedLyrics();

        void endLoading();

        void clearSkeleton();

        void showError();
    }

    private LyricsSurfacePrepareFailure() {
    }

    /** Fixed log shape: prefix, enqueue-time generation, and track id. */
    static String logMessage(int generation, String trackId) {
        return NativeSpicyLyricsHook.TAG + " prepare failed generation=" + generation
                + " track=" + trackId;
    }

    static void log(int generation, String trackId, Throwable failure) {
        XpLog.log(logMessage(generation, trackId), failure);
    }

    /**
     * Applies the fallback on the main thread for a candidate whose preparation threw.
     *
     * <p>A stopped surface or a superseded candidate leaves the loading flag to the newer
     * preparation. A failure for the track whose lyrics are already on screen only ends loading;
     * otherwise the skeleton is replaced by the existing generic error surface.
     */
    static void handle(LyricsSurfaceDocumentGate gate, LyricsSurfaceDocumentGate.Candidate candidate,
                       String trackId, Surface surface) {
        if (!surface.isRunning() || !gate.accepts(candidate, surface.currentTrackId())) return;
        surface.endLoading();
        // A failed layer update must not replace this track's displayed lyrics.
        if (surface.hasDisplayedLyrics() && trackId.equals(surface.displayedTrackId())) return;
        try {
            // Remove the skeleton first, even if attribution/error setup fails.
            surface.clearSkeleton();
            surface.showError();
        } catch (Throwable fallbackFailure) {
            XpLog.log(NativeSpicyLyricsHook.TAG + " prepare failed: error fallback", fallbackFailure);
        }
    }
}
