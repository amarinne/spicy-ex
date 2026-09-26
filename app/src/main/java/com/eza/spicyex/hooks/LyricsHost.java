package com.eza.spicyex.hooks;

import android.app.Activity;

import com.eza.spicyex.SpotifyTrack;
import com.eza.spicyex.lyrics.CacheClearKind;

/**
 * The seam between lyric surfaces and their hosting Xposed hook.
 *
 * The shell ({@link NativeSpicyShellViewImpl}) renders and
 * orchestrates lyrics; it depends on the host only for playback/track access,
 * lyric fetching, and lifecycle signals — not on the hook's internals. This lets
 * the shell live as a standalone class (and, later, be hosted by an owned
 * activity instead of the rerouted Spotify fullscreen activity).
 *
 * <p>Public so the settings surface can open the same source picker the
 * fullscreen surface uses.
 */
public interface LyricsHost {
    SpotifyTrack getCurrentTrackSafely();

    boolean isPlayerActuallyPlaying();

    long readBestMeasuredProgressMs(SpotifyTrack track, boolean playing);

    boolean seekSpotifyTo(long positionMs);

    /** Play/pause toggle and track skips via the captured MediaSession transport.
     * False when no session is captured (callers degrade to no-op visuals). */
    boolean togglePlayPause();

    boolean skipToNextTrack();

    boolean skipToPreviousTrack();

    boolean toggleSpotifySaved(String mode, SpotifyTrack expected);

    void markExplicitLyricsExit(Activity activity);

    // Re-arm the "keep lyrics activity open across track changes" window. The shell calls this
    // periodically while mounted so the suppression window never lapses mid-session; it auto-
    // expires shortly after the shell stops calling (teardown), which re-enables normal finish().
    void markLyricsKeepAlive(Activity activity);

    LyricsSessionManager.SessionSubscription subscribeLyricsSession(
            LyricsSessionManager.Listener listener);

    LyricsSessionManager.LyricsRequest fetchLyrics(
            SpotifyTrack track, NativeSpicyLyricsHook.LyricsResultCallback callback);

    /**
     * Asks the session to re-run one derived layer after its settings changed.
     *
     * The shell never starts provider work itself: the session is the only scheduler, so one
     * settings change costs one run however many surfaces are open.
     */
    void refreshLyricsLayer(com.eza.spicyex.lyrics.session.LayerKind layer);

    /**
     * Explicit owner action: reuse or generate AI for one layer, preserving what is displayed.
     *
     * @return typed acceptance or refusal reason; never a conflated boolean
     */
    com.eza.spicyex.lyrics.ai.AiRequestStartResult requestAiLyricsLayer(
            com.eza.spicyex.lyrics.session.LayerKind layer);

    /** Restores the canonical/Google baseline without scheduling another AI request. */
    void restoreLyricsLayer(com.eza.spicyex.lyrics.session.LayerKind layer);

    /** Clears durable data and invalidates the matching live-session authority. */
    void clearLyricsCache(CacheClearKind kind);

    // --- Catalog source picker: the surface renders, the session decides. ---

    /** Rows for the current track's picker, loaded off the caller thread. */
    void loadCatalogPickerRows(CatalogPickerRowsCallback callback);

    /** Renders one stored candidate and pins it; zero requests. */
    void selectCatalogCandidate(String candidateId, CatalogActionCallback callback);

    /** Drops the manual pin and re-resolves the automatic winner. */
    void resetCatalogToAuto(CatalogActionCallback callback);

    /** Fetches exactly the requested provider and records its catalog result. */
    void refreshCatalogSource(com.eza.spicyex.lyrics.catalog.CatalogSource.SourceId sourceId,
                              CatalogActionCallback callback);

    /** Runs the QQ and NetEase adapters for the current track. */
    void checkOtherCatalogSources(CatalogActionCallback callback);

    /**
     * Owner-requested climb of the whole quality chain in the built-in order, stopping at the first
     * source that answers well enough. The manual escape hatch for a track whose seat is only
     * line-timed and whose better sources were never asked.
     */
    void refreshAllCatalogSourcesInOrder(CatalogActionCallback callback);

    /** Blacklists a wrong match: deletes the row and marks its source rejected. */
    void rejectCatalogCandidate(String candidateId, CatalogActionCallback callback);

    /** Deletes one saved candidate; a deleted pin resets its track to Auto. */
    void removeCatalogCandidate(String candidateId, CatalogActionCallback callback);

    /** Deletes every saved row for the current track. */
    void deleteCatalogTrack(CatalogActionCallback callback);

    /** Settings closed: re-seat the current track when the lyric source policy changed. */
    void reconcileLyricsSources();

    interface CatalogPickerRowsCallback {
        void onRows(java.util.List<com.eza.spicyex.lyrics.catalog.CatalogPickerModel.Row> rows);
    }

    interface CatalogActionCallback {
        void onComplete(boolean success, String detail);
    }
}
