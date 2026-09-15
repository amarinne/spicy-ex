package com.eza.spicyex.hooks;

import android.content.Context;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;

import com.eza.spicyex.SpotifyTrack;
import com.eza.spicyex.xposed.XpLog;

/**
 * Spotify's own ad tracks use a "spotify:ad:..." URI (confirmed against the decompiled APK's
 * smali - it's a real, stable scheme Spotify itself branches on, not a guess). There's no way to
 * intercept or silence the ad's own audio stream directly, so instead this quietly turns the
 * device's media volume down for the duration and restores it once a real track resumes - the
 * same technique existing Spotify ad-block Xposed modules use. Runs continuously (a poll loop,
 * not tied to any one screen) so it applies to local playback everywhere, not just while the
 * lyrics fullscreen happens to be open; when playback is actually on the Connect web player
 * instead, this device isn't rendering any audio itself, so ducking its volume has no effect
 * either way - no separate "are we connected elsewhere" check is needed for that reason.
 */
final class AdMuteController {
    private static final String TAG = "[SpicyAdMute]";
    private static final String AD_URI_PREFIX = "spotify:ad:";
    // Safety net only, not the primary detection path any more (see start()) - covers whatever
    // an event this relies on might miss, at a cadence loose enough not to matter for latency.
    private static final long POLL_INTERVAL_MS = 4000L;

    private final NativeSpicyLyricsHook host;
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean ducking;
    private int savedVolume = -1;
    private boolean started;

    AdMuteController(NativeSpicyLyricsHook host, Context context) {
        this.host = host;
        this.context = context;
    }

    void start() {
        if (started) return;
        started = true;
        // Reported live as noticeably late on an 800ms poll. Spotify's own PlayerState-builder
        // hook (PlaybackBridge) already fires synchronously the instant a new PlayerState -
        // including an ad transition, which is modeled as an ordinary track with a spotify:ad:
        // URI - is built, so react to that directly instead of waiting out a poll interval.
        PlaybackBridge.setStateUpdateListener(this::check);
        main.post(this::tick);
    }

    private void check() {
        try {
            boolean enabled = Boolean.TRUE.equals(com.eza.spicyex.SpotifyPlusConfig.from(context)
                    .get(com.eza.spicyex.Settings.AUTO_MUTE_ADS));
            if (!enabled) {
                if (ducking) restore(); // toggled off mid-ad - give the volume back immediately
                return;
            }
            SpotifyTrack track = host.getCurrentTrackSafely();
            boolean isAd = track != null && track.uri != null && track.uri.startsWith(AD_URI_PREFIX);
            if (isAd && !ducking) {
                duck();
            } else if (!isAd && ducking) {
                restore();
            }
        } catch (Throwable t) {
            XpLog.log(TAG + " check failed: " + t);
        }
    }

    private void tick() {
        check();
        main.postDelayed(this::tick, POLL_INTERVAL_MS);
    }

    private void duck() {
        try {
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;
            savedVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC);
            if (savedVolume <= 0) return; // already silent/unknown - nothing to restore later either
            ducking = true;
            am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0);
            XpLog.log(TAG + " ducked (was " + savedVolume + ")");
        } catch (Throwable t) {
            XpLog.log(TAG + " duck failed: " + t);
        }
    }

    private void restore() {
        try {
            ducking = false;
            if (savedVolume < 0) return;
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) am.setStreamVolume(AudioManager.STREAM_MUSIC, savedVolume, 0);
            XpLog.log(TAG + " restored to " + savedVolume);
            savedVolume = -1;
        } catch (Throwable t) {
            XpLog.log(TAG + " restore failed: " + t);
        }
    }
}
