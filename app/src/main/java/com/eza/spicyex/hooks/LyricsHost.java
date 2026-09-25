package com.eza.spicyex.hooks;

import android.app.Activity;

import com.eza.spicyex.SpotifyTrack;
import com.eza.spicyex.lyrics.CacheClearKind;

/**
 * The seam between the native lyrics shell view and its hosting Xposed hook.
 *
 * The shell ({@link NativeSpicyShellViewImpl}) renders and
 * orchestrates lyrics; it depends on the host only for playback/track access,
 * lyric fetching, and lifecycle signals — not on the hook's internals. This lets
 * the shell live as a standalone class (and, later, be hosted by an owned
 * activity instead of the rerouted Spotify fullscreen activity).
 */
interface LyricsHost {
    SpotifyTrack getCurrentTrackSafely();

    boolean isPlayerActuallyPlaying();

    long readBestMeasuredProgressMs(SpotifyTrack track, boolean playing);

    boolean seekSpotifyTo(long positionMs);

    /** Whether a seek would currently be honored (ACTION_SEEK_TO advertised right now) - lets
     *  callers hide/disable seek affordances proactively instead of finding out after a silent
     *  rejection. See PlaybackBridge#canSeek. */
    boolean canSeek();

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
}
