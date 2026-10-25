package com.eza.spicyex.hooks;

/**
 * Playback transport commands a lyric surface issues through its host.
 *
 * <p>{@link LyricsHost} provides these along with lyric, session, and lifecycle access. A
 * surface that only drives playback — such as the track-info readout's artwork gestures and
 * overlay button — depends on this narrower capability instead of the whole lyric host.
 */
public interface PlaybackTransportHost {
    /** Play/pause toggle and track skips via the captured MediaSession transport.
     * False when no session is captured (callers degrade to no-op visuals). */
    boolean togglePlayPause();

    boolean skipToNextTrack();

    boolean skipToPreviousTrack();
}
