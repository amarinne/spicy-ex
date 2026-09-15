package com.eza.spicyex.hooks;

import android.media.AudioTrack;
import android.media.audiofx.Visualizer;
import android.os.Handler;
import android.os.Looper;

import com.eza.spicyex.xposed.XpHooks;
import com.eza.spicyex.xposed.XpLog;

/**
 * Real waveform-driven audio level, replacing the BPM-guess pulse with the track's actual output.
 *
 * <p>Attaching a {@link Visualizer} to session 0 (the device's master output mix) - the technique
 * projectM's Android port uses for cross-app capture - fails here with "Cannot initialize
 * Visualizer engine, error: -3" (confirmed live on this device/Android version): since Android 10,
 * visualizing session 0 or another app's session needs the signature-level
 * android.permission.CAPTURE_AUDIO_OUTPUT, which nothing but a system app can hold, so that route
 * is closed to us even though our code runs under Spotify's UID.
 *
 * <p>What's actually legal for us - and simpler - is visualizing a session Spotify's own process
 * created, i.e. "an app visualizing its own audio", same as its built-in equalizer already does.
 * The first attempt at that hooked Spotify's own
 * com.spotify.playbacknative.AudioEffectsListener.onAudioTrackCreated/onAudioTrackDestroyed to
 * learn its session id, but that internal hook never fired with a usable session on this build.
 * Hooking {@link AudioTrack#play()} directly instead is far more reliable: it's a stable platform
 * API (not an app-internal class that can go unused/renamed/restructured build to build), and
 * since this whole process only ever creates AudioTrack instances for Spotify's own playback,
 * every call already carries a legitimately-ours session id via {@link AudioTrack#getAudioSessionId()}.
 */
final class AudioReactiveController {
    private static final String TAG = "[SpicyAudioReactive]";
    private static final int CAPTURE_RATE_HZ = 24;
    private static final float SMOOTHING_UP = 0.55f;   // rises fast (transient attack)
    private static final float SMOOTHING_DOWN = 0.12f; // decays slower (readable trailing edge)

    interface LevelListener {
        void onLevel(float level0to1);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final LevelListener listener;
    private Visualizer visualizer;
    private int attachedSessionId = -1;
    private float smoothedLevel;
    private boolean started;
    // Gates whether attach() actually runs a Visualizer, independent of the AudioTrack#play hook
    // itself (that hook always stays installed - cheap, just intercepting method calls - but the
    // Visualizer it can trigger is real, continuous per-frame FFT/waveform work for as long as
    // it's enabled). Without this the Visualizer ran for the entire lifetime of every Spotify
    // track, including all the time music plays with the lyrics screen closed or in another app
    // entirely and nothing is even reading the level it produces - a real, avoidable battery cost
    // matching the live "Spotify drains the battery" complaint, and one this feature introduced.
    private volatile boolean listeningEnabled;
    private int lastKnownSessionId = -1;

    AudioReactiveController(LevelListener listener) {
        this.listener = listener;
    }

    /** Called from the lyrics screen's own mount/unmount lifecycle - see NativeSpicyLyricsHook.
     *  Enabling re-attaches immediately if a session is already known (playback already going);
     *  disabling tears the Visualizer down right away rather than waiting for the next event. */
    void setListeningEnabled(boolean enabled) {
        main.post(() -> {
            listeningEnabled = enabled;
            if (enabled) {
                if (lastKnownSessionId > 0) attach(lastKnownSessionId);
            } else {
                teardown();
            }
        });
    }

    void start() {
        if (started) return;
        started = true;
        try {
            XpHooks.findAfter(AudioTrack.class, "play", "audioReactive:AudioTrack#play",
                    param -> {
                        try {
                            AudioTrack track = (AudioTrack) param.thisObject;
                            int sessionId = track.getAudioSessionId();
                            if (sessionId > 0) {
                                lastKnownSessionId = sessionId;
                                if (listeningEnabled && sessionId != attachedSessionId) {
                                    main.post(() -> attach(sessionId));
                                }
                            }
                        } catch (Throwable ignored) {
                        }
                    });
            XpLog.log(TAG + " AudioTrack#play hook installed");
        } catch (Throwable t) {
            XpLog.log(TAG + " AudioTrack#play hook failed: " + t);
        }
    }

    private void attach(int sessionId) {
        teardown();
        try {
            Visualizer v = new Visualizer(sessionId);
            int[] captureRange = Visualizer.getCaptureSizeRange();
            int captureSize = captureRange != null && captureRange.length == 2
                    ? Math.max(captureRange[0], Math.min(captureRange[1], 256)) : 256;
            v.setCaptureSize(captureSize);
            int rate = Math.min(Visualizer.getMaxCaptureRate(), CAPTURE_RATE_HZ * 1000);
            v.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                @Override
                public void onWaveFormDataCapture(Visualizer vis, byte[] waveform, int samplingRate) {
                    handleWaveform(waveform);
                }

                @Override
                public void onFftDataCapture(Visualizer vis, byte[] fft, int samplingRate) {
                }
            }, rate, true, false);
            v.setEnabled(true);
            visualizer = v;
            attachedSessionId = sessionId;
            XpLog.log(TAG + " attached to session=" + sessionId);
        } catch (Throwable t) {
            // Seen on some OEM/driver combinations (e.g. no free Visualizer engine slot, or this
            // particular session doesn't support effects) - background just stays non-reactive.
            XpLog.log(TAG + " attach failed for session=" + sessionId + ": " + t);
            visualizer = null;
            attachedSessionId = -1;
        }
    }

    private void handleWaveform(byte[] waveform) {
        if (waveform == null || waveform.length == 0) return;
        // Unsigned 8-bit PCM centered at 128; RMS deviation from center as a 0..1-ish energy proxy.
        double sumSquares = 0;
        for (byte b : waveform) {
            int v = (b & 0xFF) - 128;
            sumSquares += (double) v * v;
        }
        double rms = Math.sqrt(sumSquares / waveform.length);
        float raw = (float) Math.min(1.0, rms / 48.0); // 48 ~= a loud passage's typical RMS here
        float rate = raw > smoothedLevel ? SMOOTHING_UP : SMOOTHING_DOWN;
        smoothedLevel += (raw - smoothedLevel) * rate;
        float delivered = smoothedLevel;
        main.post(() -> listener.onLevel(delivered));
    }

    private void teardown() {
        Visualizer v = visualizer;
        visualizer = null;
        attachedSessionId = -1;
        smoothedLevel = 0f;
        if (v == null) return;
        try {
            v.setEnabled(false);
            v.release();
        } catch (Throwable ignored) {
        }
    }

}
