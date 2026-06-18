package com.eza.spicyex.lyrics;

import android.os.SystemClock;

import com.eza.spicyex.SpotifyTrack;
import static com.eza.spicyex.lyrics.LyricUtils.safe;

/** Smooths Spotify's coarse playback progress samples for lyrics animation. */
public final class LyricsPlaybackClock {
    private static final long[] RESYNC_TIMINGS_MS = new long[]{50, 100, 150, 750};
    private static final long STEADY_RESYNC_MS = 33;
    private static final long JITTER_RESYNC_THRESHOLD_MS = 500;
    private static final double JITTER_TIME_CONSTANT_MS = 300d;
    private static final long PROGRESS_POSITION_OFFSET_MS = 25;

    private final Measurer measurer;
    private String trackUri = "";
    private long sampledPositionMs = -1;
    private long sampledAtElapsedMs = 0;
    private long predictedPositionMs = -1;
    private long predictedUpdatedAtElapsedMs = 0;
    private long nextResyncAtElapsedMs = 0;
    private int syncIndex = 0;

    public LyricsPlaybackClock(Measurer measurer) {
        this.measurer = measurer;
    }

    public void reset(String uri) {
        trackUri = safe(uri);
        sampledPositionMs = -1;
        sampledAtElapsedMs = 0;
        predictedPositionMs = -1;
        predictedUpdatedAtElapsedMs = 0;
        nextResyncAtElapsedMs = 0;
        syncIndex = 0;
    }

    public void forcePosition(long positionMs, boolean playing) {
        long now = SystemClock.elapsedRealtime();
        long clamped = clampToTrack(positionMs, null);
        sampledPositionMs = clamped;
        sampledAtElapsedMs = now;
        predictedPositionMs = clamped;
        predictedUpdatedAtElapsedMs = now;
        nextResyncAtElapsedMs = now + nextDelayMs(playing);
    }

    public long getPosition(SpotifyTrack track, boolean playing) {
        String uri = track == null ? "" : safe(track.uri);
        if (!safe(trackUri).equals(uri)) reset(uri);

        long now = SystemClock.elapsedRealtime();
        if (sampledPositionMs < 0 || now >= nextResyncAtElapsedMs) {
            long measured = measure(track, playing);
            if (measured >= 0) applyMeasuredSample(track, measured, playing, now);
        }

        if (sampledPositionMs < 0) {
            long fallback = measure(track, playing);
            return fallback < 0 ? -1 : clampToTrack(fallback + (playing ? PROGRESS_POSITION_OFFSET_MS : 0), track);
        }

        long measuredNow = sampledPositionMs;
        if (playing) measuredNow += Math.max(0, now - sampledAtElapsedMs);
        measuredNow = clampToTrack(measuredNow, track);

        if (predictedPositionMs < 0 || !playing) {
            predictedPositionMs = measuredNow;
            predictedUpdatedAtElapsedMs = now;
            return clampToTrack(predictedPositionMs, track);
        }

        long elapsed = Math.max(0, now - predictedUpdatedAtElapsedMs);
        long predictedNow = clampToTrack(predictedPositionMs + elapsed, track);
        long error = measuredNow - predictedNow;
        if (Math.abs(error) > JITTER_RESYNC_THRESHOLD_MS) {
            predictedNow = measuredNow;
        } else {
            double alpha = 1d - Math.exp(-(double) elapsed / JITTER_TIME_CONSTANT_MS);
            predictedNow = clampToTrack(Math.round(predictedNow + error * alpha), track);
        }

        predictedPositionMs = predictedNow;
        predictedUpdatedAtElapsedMs = now;
        long output = predictedNow + PROGRESS_POSITION_OFFSET_MS;
        return clampToTrack(output, track);
    }

    private long measure(SpotifyTrack track, boolean playing) {
        return measurer == null ? -1 : measurer.readBestMeasuredProgressMs(track, playing);
    }

    private void applyMeasuredSample(SpotifyTrack track, long measured, boolean playing, long now) {
        sampledPositionMs = clampToTrack(measured, track);
        sampledAtElapsedMs = now;
        if (predictedPositionMs < 0 || !playing) {
            predictedPositionMs = sampledPositionMs;
            predictedUpdatedAtElapsedMs = now;
        }
        nextResyncAtElapsedMs = now + nextDelayMs(playing);
    }

    private long nextDelayMs(boolean playing) {
        if (!playing) return 250;
        long delay = syncIndex < RESYNC_TIMINGS_MS.length ? RESYNC_TIMINGS_MS[syncIndex] : STEADY_RESYNC_MS;
        if (syncIndex < RESYNC_TIMINGS_MS.length) syncIndex++;
        return delay;
    }

    private long clampToTrack(long positionMs, SpotifyTrack track) {
        long clamped = Math.max(0, positionMs);
        long duration = track == null ? 0 : Math.max(0, track.duration);
        if (duration > 0) clamped = Math.min(clamped, duration);
        return clamped;
    }

    public interface Measurer {
        long readBestMeasuredProgressMs(SpotifyTrack track, boolean playing);
    }
}
