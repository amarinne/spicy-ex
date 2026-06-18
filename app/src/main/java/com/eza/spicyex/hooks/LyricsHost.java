package com.eza.spicyex.hooks;

import android.app.Activity;

import com.eza.spicyex.SpotifyTrack;

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

    void markExplicitLyricsExit(Activity activity);

    // Re-arm the "keep lyrics activity open across track changes" window. The shell calls this
    // periodically while mounted so the suppression window never lapses mid-session; it auto-
    // expires shortly after the shell stops calling (teardown), which re-enables normal finish().
    void markLyricsKeepAlive(Activity activity);

    void fetchLyrics(Activity activity, SpotifyTrack track, int generation,
                     NativeSpicyLyricsHook.LyricsResultCallback callback);
}
