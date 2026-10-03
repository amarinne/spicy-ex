package com.eza.spicyex.hooks;

import android.media.AudioTimestamp;
import android.media.AudioTrack;
import android.os.SystemClock;
import android.util.SparseArray;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import com.eza.spicyex.xposed.XpLog;

/**
 * How far the sound being heard trails the position Spotify reports.
 *
 * <p>Spotify's own player renders through a Java AudioTrack owned by
 * {@code com.spotify.playbacknative.AudioDriver} (a JNI class, so its name and members survive
 * obfuscation). The native core derives the playback position from that track's playback head -
 * the frames the mixer has taken - which runs ahead of what leaves the speaker by the output
 * path's own latency: the mixer and HAL buffers (around 100 ms on a deep-buffer output) and, on
 * Bluetooth, the codec and the headset (another 150-300 ms). AudioTrack#getTimestamp reports
 * the frame actually being presented, so the gap between the two is that latency, measured live
 * for whatever output is in use. Nothing is hooked: the driver is read from its static session map.
 */
final class AudioOutputLatency {
    private static final String DRIVER_CLASS = "com.spotify.playbacknative.AudioDriver";
    /** Re-measured at most this often; the latency only changes with the output route. */
    private static final long SAMPLE_INTERVAL_MS = 500L;
    private static final long MAX_LATENCY_MS = 600L;

    private final ClassLoader classLoader;
    private final AudioTimestamp timestamp = new AudioTimestamp();
    private boolean resolved;
    private Field sessionMapField;
    private Field currentSessionField;
    private Method getAudioTrack;
    private long sampledAtMs = Long.MIN_VALUE / 2;
    private float latencyMs = -1f;
    private boolean logged;

    AudioOutputLatency(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    /** The smoothed output latency in ms, or 0 while no local Spotify track is playing. */
    synchronized long latencyMs() {
        long now = SystemClock.uptimeMillis();
        if (now - sampledAtMs >= SAMPLE_INTERVAL_MS) {
            sampledAtMs = now;
            sample();
        }
        return latencyMs <= 0f ? 0L : Math.round(latencyMs);
    }

    // ---- Audio clock -----------------------------------------------------------------------
    // Spotify reports its position only when it builds a new PlayerState (play, seek, track
    // change, ...); between those the position is extrapolated with the wall clock. The audio
    // hardware runs on its own clock, so over a song the two drift apart by up to a few hundred
    // ms, and the lyrics slowly slip. Instead, each PlayerState is anchored to the frame the
    // playback head was at when it was taken, and the position from then on is counted in the
    // frames actually presented to the output - the sound being heard, latency included.

    /** How often AudioTrack#getTimestamp is re-read; it is extrapolated in between. */
    private static final long TIMESTAMP_REFRESH_MS = 200L;
    /** Further from the wall-clock estimate than this, the frames no longer belong to the
     *  anchored state (a flush, a route change): fall back and re-anchor. */
    private static final long MAX_CLOCK_DISAGREEMENT_MS = 1000L;

    private Object anchorKey;
    private AudioTrack anchorTrack;
    private long anchorPositionMs;
    private long anchorFrames;
    private long timestampFrames = -1L;
    private long timestampNanos;
    private long timestampReadAtMs = Long.MIN_VALUE / 2;
    private AudioTrack timestampTrack;
    private AudioTrack cachedTrack;
    private long cachedTrackAtMs = Long.MIN_VALUE / 2;
    private boolean clockLogged;

    /**
     * The position being heard, counted from the anchored PlayerState in presented audio frames;
     * -1 when it cannot be (no local track playing, or the frames disagree with the state).
     *
     * @param stateKey the PlayerState object (identity: a new one re-anchors)
     * @param basePositionMs its positionAsOfTimestamp
     * @param stateWallMs its timestamp (System.currentTimeMillis)
     * @param wallEstimateMs the wall-clock extrapolation, as a sanity bound
     */
    synchronized long heardPositionMs(Object stateKey, long basePositionMs, long stateWallMs,
                                      long wallEstimateMs) {
        AudioTrack track = cachedTrack();
        if (track == null || track.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
            anchorKey = null;
            return -1L;
        }
        int rate = track.getSampleRate();
        if (rate <= 0) return -1L;
        if (stateKey != anchorKey || track != anchorTrack) {
            long head = track.getPlaybackHeadPosition() & 0xffffffffL;
            long sinceState = Math.max(0L, System.currentTimeMillis() - stateWallMs);
            anchorFrames = head - sinceState * rate / 1000L;
            anchorPositionMs = basePositionMs;
            anchorKey = stateKey;
            anchorTrack = track;
        }
        long presented = presentedFrames(track, rate);
        if (presented < 0) return -1L;
        long heard = anchorPositionMs + (presented - anchorFrames) * 1000L / rate;
        long expected = wallEstimateMs - latencyMs();
        if (Math.abs(heard - expected) > MAX_CLOCK_DISAGREEMENT_MS) {
            anchorKey = null;
            return -1L;
        }
        if (!clockLogged) {
            clockLogged = true;
            XpLog.log(NativeSpicyLyricsHook.TAG + " audio clock in use: heard=" + heard
                    + "ms wall=" + wallEstimateMs + "ms");
        }
        return Math.max(0L, heard);
    }

    private long presentedFrames(AudioTrack track, int rate) {
        long nowMs = SystemClock.uptimeMillis();
        if (track != timestampTrack || nowMs - timestampReadAtMs >= TIMESTAMP_REFRESH_MS) {
            timestampReadAtMs = nowMs;
            timestampTrack = track;
            if (track.getTimestamp(timestamp)) {
                timestampFrames = timestamp.framePosition;
                timestampNanos = timestamp.nanoTime;
            } else {
                timestampFrames = -1L;
            }
        }
        if (timestampFrames < 0) return -1L;
        return timestampFrames + (System.nanoTime() - timestampNanos) * rate / 1_000_000_000L;
    }

    /** The driver's track, re-resolved through reflection at most every SAMPLE_INTERVAL_MS. */
    private AudioTrack cachedTrack() {
        long now = SystemClock.uptimeMillis();
        if (now - cachedTrackAtMs >= SAMPLE_INTERVAL_MS) {
            cachedTrackAtMs = now;
            cachedTrack = currentTrack();
        }
        return cachedTrack;
    }

    private void sample() {
        AudioTrack track = cachedTrack();
        if (track == null || track.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
            // Paused, remote (Connect) or not created: nothing local is waiting to be heard.
            latencyMs = -1f;
            return;
        }
        int rate = track.getSampleRate();
        if (rate <= 0 || !track.getTimestamp(timestamp)) return;
        long head = track.getPlaybackHeadPosition() & 0xffffffffL;
        long presented = timestamp.framePosition
                + (System.nanoTime() - timestamp.nanoTime) * rate / 1_000_000_000L;
        float measured = (head - presented) * 1000f / rate;
        // Right after a start or flush the two counters briefly disagree; skip those samples.
        if (measured < 0f || measured > MAX_LATENCY_MS) return;
        latencyMs = latencyMs < 0f ? measured : latencyMs + (measured - latencyMs) * 0.3f;
        if (!logged) {
            logged = true;
            XpLog.log(NativeSpicyLyricsHook.TAG + " audio output latency " + Math.round(measured)
                    + "ms at " + rate + "Hz");
        }
    }

    private AudioTrack currentTrack() {
        if (!resolved) {
            resolved = true;
            try {
                Class<?> driver = Class.forName(DRIVER_CLASS, false, classLoader);
                sessionMapField = driver.getDeclaredField("sSessionToAudioDriverMap");
                sessionMapField.setAccessible(true);
                currentSessionField = driver.getDeclaredField("sCurrentAudioSession");
                currentSessionField.setAccessible(true);
                getAudioTrack = driver.getMethod("getAudioTrack");
            } catch (Throwable t) {
                // Structural: the driver symbols are gone; never retry them.
                sessionMapField = null;
                if (!logged) {
                    logged = true;
                    XpLog.log(NativeSpicyLyricsHook.TAG + " audio output latency unavailable: "
                            + t, t);
                }
                return null;
            }
        }
        if (sessionMapField == null) return null;
        try {
            Object session = currentSessionField.get(null);
            SparseArray<?> drivers = (SparseArray<?>) sessionMapField.get(null);
            if (drivers == null) return null;
            Object[] candidates;
            synchronized (drivers) {
                if (session instanceof Integer) {
                    candidates = new Object[]{drivers.get((Integer) session)};
                } else {
                    candidates = new Object[drivers.size()];
                    for (int i = 0; i < candidates.length; i++) candidates[i] = drivers.valueAt(i);
                }
            }
            AudioTrack fallback = null;
            for (Object driver : candidates) {
                Object track = driver == null ? null : getAudioTrack.invoke(driver);
                if (!(track instanceof AudioTrack)) continue;
                if (((AudioTrack) track).getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                    return (AudioTrack) track;
                }
                fallback = (AudioTrack) track;
            }
            return fallback;
        } catch (Throwable t) {
            // Transient sampling failure: keep the resolved symbols so later samples retry.
            if (!logged) {
                logged = true;
                XpLog.log(NativeSpicyLyricsHook.TAG + " audio output latency unavailable: "
                        + t, t);
            }
            return null;
        }
    }
}
