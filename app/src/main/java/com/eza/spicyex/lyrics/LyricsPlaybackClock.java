package com.eza.spicyex.lyrics;

import android.os.SystemClock;

import com.eza.spicyex.SpotifyTrack;
import static com.eza.spicyex.lyrics.LyricUtils.safe;

import java.util.function.LongSupplier;

/** Smooths Spotify's coarse playback progress samples for lyrics animation. */
public final class LyricsPlaybackClock {
    private static final long[] RESYNC_TIMINGS_MS = new long[]{50, 100, 150, 750};
    private static final long STEADY_RESYNC_MS = 33;
    private static final long JITTER_RESYNC_THRESHOLD_MS = 500;
    private static final double JITTER_TIME_CONSTANT_MS = 300d;
    private static final long PROGRESS_POSITION_OFFSET_MS = 25;

    private final Measurer measurer;
    private final LongSupplier elapsedRealtimeMs;
    private String trackUri = "";
    private long sampledPositionMs = -1;
    private long sampledAtElapsedMs = 0;
    private double anchorRate = Double.NaN;
    private long predictedPositionMs = -1;
    private long predictedUpdatedAtElapsedMs = 0;
    private long nextResyncAtElapsedMs = 0;
    private int syncIndex = 0;

    public LyricsPlaybackClock(Measurer measurer) {
        this(measurer, SystemClock::elapsedRealtime);
    }

    /** Deterministic seam: monotonic clock in ms on the same basis as elapsedRealtime. */
    public LyricsPlaybackClock(Measurer measurer, LongSupplier elapsedRealtimeMs) {
        this.measurer = measurer;
        this.elapsedRealtimeMs = elapsedRealtimeMs != null ? elapsedRealtimeMs : SystemClock::elapsedRealtime;
    }

    public void reset(String uri) {
        trackUri = safe(uri);
        sampledPositionMs = -1;
        sampledAtElapsedMs = 0;
        anchorRate = Double.NaN;
        predictedPositionMs = -1;
        predictedUpdatedAtElapsedMs = 0;
        nextResyncAtElapsedMs = 0;
        syncIndex = 0;
    }

    public void forcePosition(long positionMs, boolean playing) {
        long now = elapsedRealtimeMs.getAsLong();
        double rate = effectiveRate(playing);
        long clamped = clampToTrack(positionMs, null);
        sampledPositionMs = clamped;
        sampledAtElapsedMs = now;
        anchorRate = rate;
        predictedPositionMs = clamped;
        predictedUpdatedAtElapsedMs = now;
        nextResyncAtElapsedMs = now + nextDelayMs(playing);
    }

    public long getPosition(SpotifyTrack track, boolean playing) {
        String uri = track == null ? "" : safe(track.uri);
        if (!safe(trackUri).equals(uri)) reset(uri);

        long now = elapsedRealtimeMs.getAsLong();
        double rate = effectiveRate(playing);
        if (Double.isNaN(anchorRate)) {
            anchorRate = rate;
        } else if (Math.abs(anchorRate - rate) > 1e-9) {
            double previousRate = anchorRate;
            anchorRate = rate;
            // Fallback: fold progress accrued at the previous rate into both anchors so a
            // buffering stop freezes at the transition point and a resume/rate change
            // continues from it instead of jumping.
            if (previousRate > 0d) {
                if (sampledPositionMs >= 0) {
                    sampledPositionMs += Math.round(Math.max(0, now - sampledAtElapsedMs) * previousRate);
                }
                if (predictedPositionMs >= 0) {
                    predictedPositionMs += Math.round(
                            Math.max(0, now - predictedUpdatedAtElapsedMs) * previousRate);
                }
            }
            sampledAtElapsedMs = now;
            predictedUpdatedAtElapsedMs = now;
            // The transition can land before the next scheduled resync with a stale anchor.
            // Take one immediate measurement; on success re-seat prediction on it so no
            // smoothing carries an obsolete buffered/paused position forward.
            long transitionMeasured = measure(track, playing);
            if (transitionMeasured >= 0) {
                sampledPositionMs = clampToTrack(transitionMeasured, track);
                predictedPositionMs = sampledPositionMs;
                nextResyncAtElapsedMs = now + nextDelayMs(playing);
            }
        }
        if (sampledPositionMs < 0 || now >= nextResyncAtElapsedMs) {
            long measured = measure(track, playing);
            if (measured >= 0) applyMeasuredSample(track, measured, playing, now, rate);
        }

        if (sampledPositionMs < 0) {
            long fallback = measure(track, playing);
            return fallback < 0 ? -1 : clampToTrack(fallback + (rate > 0d ? PROGRESS_POSITION_OFFSET_MS : 0), track);
        }

        long measuredNow = sampledPositionMs;
        if (rate > 0d) measuredNow += Math.round(Math.max(0, now - sampledAtElapsedMs) * rate);
        measuredNow = clampToTrack(measuredNow, track);

        if (predictedPositionMs < 0 || rate <= 0d) {
            predictedPositionMs = measuredNow;
            predictedUpdatedAtElapsedMs = now;
            return clampToTrack(predictedPositionMs, track);
        }

        long elapsed = Math.max(0, now - predictedUpdatedAtElapsedMs);
        long predictedNow = clampToTrack(predictedPositionMs + Math.round(elapsed * rate), track);
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

    private void applyMeasuredSample(SpotifyTrack track, long measured, boolean playing, long now,
            double rate) {
        sampledPositionMs = clampToTrack(measured, track);
        sampledAtElapsedMs = now;
        anchorRate = rate;
        if (predictedPositionMs < 0 || rate <= 0d) {
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

        /** Effective playback rate: 0 while paused/buffering, the reported speed otherwise. */
        default double readEffectiveRate(boolean playing) {
            return playing ? 1d : 0d;
        }
    }

    /**
     * Effective rate for this frame: 0 while paused/buffering, the reported speed while
     * genuinely advancing. Paused short-circuits to zero even if the measurer reports a
     * positive rate; junk (NaN, negative, absurd) falls back to 1 while active.
     */
    private double effectiveRate(boolean playing) {
        if (!playing) return 0d;
        double rate = 1d;
        if (measurer != null) {
            try {
                rate = measurer.readEffectiveRate(true);
            } catch (Throwable ignored) {
                rate = 1d;
            }
        }
        if (Double.isNaN(rate) || rate < 0d || rate > 8d) return 1d;
        return rate;
    }
}
