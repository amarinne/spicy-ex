package com.eza.spicyex.hooks;

import android.content.Context;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;

import java.util.Map;
import java.util.WeakHashMap;

import com.eza.spicyex.SpotifyTrack;
import com.eza.spicyex.xposed.XpHooks;
import com.eza.spicyex.xposed.XpLog;

/**
 * What happens while an ad plays: nothing, silence, or soft music in its place
 * ({@link com.eza.spicyex.Settings#AD_MODE}).
 *
 * <p>Spotify's own ad tracks use a "spotify:ad:..." URI (confirmed against the decompiled APK's
 * smali - it's a real, stable scheme Spotify itself branches on, not a guess). There's no way to
 * intercept or silence the ad's own audio stream directly, so instead this hooks Spotify's
 * {@link AudioTrack} volume calls and clamps only the ad track to silence. The phone's global
 * media volume is never changed. Runs continuously (a poll loop,
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
    // check() runs on every PlayerState Spotify builds (see start()), which is many times a
    // second during playback. SpotifyPlusConfig.from() allocates a fresh wrapper per call, so
    // building one there meant garbage on that hot path for every single state update - and for
    // most people the answer is an immediate "disabled, nothing to do". Held once instead; it
    // wraps SharedPreferences, so reads through it stay live and toggling still applies at once.
    private final com.eza.spicyex.SpotifyPlusConfig config;
    /** Spotify may reuse the same AudioTrack after an ad. Keep the last non-ad gain so a mute
     *  applied during the ad cannot remain stuck when Spotify skips its next setVolume call. */
    private final Map<AudioTrack, Volume> normalVolumes = new WeakHashMap<>();
    private final AdMusicPlayer music = new AdMusicPlayer();
    /** Our own volume writes (the fade back in after an ad) must not be remembered as Spotify's. */
    private final ThreadLocal<Boolean> ownVolumeWrite = new ThreadLocal<>();
    private volatile boolean adActive;
    private boolean started;
    private int restoreGeneration;
    private static final long RESTORE_FADE_MS = 900L;
    private static final long RESTORE_FADE_STEP_MS = 30L;
    /** How long before the last ad ends the music starts its ending, so the ending (at most
     *  AdMusicPlayer's outro plus its fade) is over by the time the song comes in. */
    private static final long MUSIC_END_LEAD_MS = 2600L;
    private final com.eza.spicyex.lyrics.LyricsPlaybackClock adClock;
    private final Runnable recheck = this::check;

    AdMuteController(NativeSpicyLyricsHook host, Context context) {
        this.host = host;
        this.context = context;
        this.config = com.eza.spicyex.SpotifyPlusConfig.from(context);
        this.adClock = new com.eza.spicyex.lyrics.LyricsPlaybackClock(host::readBestMeasuredProgressMs);
    }

    void start() {
        if (started) return;
        started = true;
        installAudioTrackHooks();
        // Reported live as noticeably late on an 800ms poll. Spotify's own PlayerState-builder
        // hook (PlaybackBridge) already fires synchronously the instant a new PlayerState -
        // including an ad transition, which is modeled as an ordinary track with a spotify:ad:
        // URI - is built, so react to that directly instead of waiting out a poll interval.
        PlaybackBridge.setStateUpdateListener(this::check);
        main.post(this::tick);
    }

    private void check() {
        try {
            String mode = config.get(com.eza.spicyex.Settings.AD_MODE);
            if (com.eza.spicyex.Settings.AD_MODE_OFF.equals(mode)) {
                if (adActive) endAd();
                return;
            }
            SpotifyTrack track = host.getCurrentTrackSafely();
            // Between two tracks - notably between two ads in one break - there is briefly no
            // track at all. Hold the current state through that gap instead of ending the ad and
            // starting it again, which would restart the music and let a blip of volume through.
            if (track == null || track.uri == null || track.uri.isEmpty()) return;
            boolean isAd = track.uri.startsWith(AD_URI_PREFIX);
            if (isAd && !adActive) startAd();
            else if (!isAd && adActive) endAd();
            if (adActive) updateMusic(mode, track);
        } catch (Throwable t) {
            XpLog.log(TAG + " check failed: " + t);
        }
    }

    private void startAd() {
        adActive = true;
        restoreGeneration++; // cancels a fade back in still running from the previous ad
        synchronized (normalVolumes) {
            for (AudioTrack track : normalVolumes.keySet()) setOwnVolume(track, 0f, 0f);
        }
    }

    private void endAd() {
        adActive = false;
        main.removeCallbacks(recheck);
        music.fadeOutAndStop();
        fadeInNormalVolumes();
    }

    private void updateMusic(String mode, SpotifyTrack track) {
        if (!com.eza.spicyex.Settings.AD_MODE_MUSIC.equals(mode)) {
            if (music.isPlaying()) music.fadeOutAndStop();
            return;
        }
        // The media session does not reliably report an ad as playing (outside the lyrics screen
        // nothing else corrected for that, so the music sat at zero gain). Only an explicit
        // pause in Spotify's own PlayerState holds it.
        if (host.isPlayerStatePaused()) {
            music.hold();
            return;
        }
        // On the last ad of a break the music ends with the ad instead of on the song's first
        // note: without knowing the break's length it only stopped once the song had started,
        // and its ending then played over it.
        main.removeCallbacks(recheck);
        long remaining = remainingMs(track);
        AdBreakInfo info = AdBreakInfo.current(track.uri);
        if (info != null && info.isLast() && remaining >= 0 && remaining <= MUSIC_END_LEAD_MS) {
            if (music.isPlaying()) music.fadeOutAndStop();
            return;
        }
        music.setTheme(config.get(com.eza.spicyex.Settings.AD_MUSIC_THEME));
        music.fadeIn();
        if (remaining > 0) {
            // Look again just in time for the ending (and meanwhile once a second, so a break
            // position that only shows up on screen later is still caught).
            long wait = info != null && info.isLast()
                    ? Math.max(50L, remaining - MUSIC_END_LEAD_MS)
                    : Math.min(1000L, Math.max(50L, remaining - MUSIC_END_LEAD_MS));
            main.postDelayed(recheck, wait);
        }
    }

    /** Time left in the current ad, or -1 when unknown. */
    private long remainingMs(SpotifyTrack track) {
        if (track == null || track.duration <= 0) return -1;
        long position = adClock.getPosition(track, !host.isPlayerStatePaused());
        return position < 0 ? -1 : Math.max(0L, track.duration - position);
    }

    private void tick() {
        check();
        main.postDelayed(this::tick, POLL_INTERVAL_MS);
    }

    /** Mutes Spotify's playback track without changing the phone's global media volume. */
    private void installAudioTrackHooks() {
        try {
            XpHooks.findAfter(AudioTrack.class, "play", "adMute:AudioTrack#play", param -> {
                AudioTrack audioTrack = (AudioTrack) param.thisObject;
                if (AdMusicPlayer.isOwnTrack(audioTrack)) return;
                if (adActive) {
                    // Spotify may never have set this track's volume; its normal level is then
                    // full volume, and it must be known or the fade back in has nothing to reach.
                    rememberVolumeIfMissing(audioTrack, 1f, 1f);
                    setOwnVolume(audioTrack, 0f, 0f);
                } else {
                    restoreNormalVolume(audioTrack);
                }
            });
            XpHooks.findBefore(AudioTrack.class, "setVolume", "adMute:AudioTrack#setVolume",
                    param -> {
                        AudioTrack audioTrack = (AudioTrack) param.thisObject;
                        if (isOwnWrite(audioTrack)) return;
                        float requested = ((Float) param.args[0]);
                        if (!adActive) {
                            rememberVolume(audioTrack, requested, requested);
                        } else {
                            rememberVolumeIfMissing(audioTrack, requested, requested);
                            param.args[0] = 0f;
                        }
                    }, float.class);
            XpHooks.findBefore(AudioTrack.class, "setStereoVolume",
                    "adMute:AudioTrack#setStereoVolume", param -> {
                        AudioTrack audioTrack = (AudioTrack) param.thisObject;
                        if (isOwnWrite(audioTrack)) return;
                        float left = ((Float) param.args[0]);
                        float right = ((Float) param.args[1]);
                        if (adActive) {
                            rememberVolumeIfMissing(audioTrack, left, right);
                            param.args[0] = 0f;
                            param.args[1] = 0f;
                        } else {
                            rememberVolume(audioTrack, left, right);
                        }
                    }, float.class, float.class);
            XpLog.log(TAG + " per-track AudioTrack mute hooks installed");
        } catch (Throwable t) {
            XpLog.log(TAG + " AudioTrack mute hooks failed: " + t);
        }
    }

    private void rememberVolumeIfMissing(AudioTrack track, float left, float right) {
        synchronized (normalVolumes) {
            if (!normalVolumes.containsKey(track)) normalVolumes.put(track, new Volume(left, right));
        }
    }

    private void rememberVolume(AudioTrack track, float left, float right) {
        synchronized (normalVolumes) {
            normalVolumes.put(track, new Volume(left, right));
        }
    }

    private boolean isOwnWrite(AudioTrack track) {
        return AdMusicPlayer.isOwnTrack(track) || Boolean.TRUE.equals(ownVolumeWrite.get());
    }

    /** Brings Spotify's audio back over {@link #RESTORE_FADE_MS} instead of cutting it in. */
    private void fadeInNormalVolumes() {
        int generation = ++restoreGeneration;
        long steps = RESTORE_FADE_MS / RESTORE_FADE_STEP_MS;
        for (long step = 1; step <= steps; step++) {
            float fraction = step / (float) steps;
            float eased = fraction * fraction * (3f - 2f * fraction);
            main.postDelayed(() -> {
                if (generation != restoreGeneration || adActive) return;
                synchronized (normalVolumes) {
                    for (Map.Entry<AudioTrack, Volume> entry : normalVolumes.entrySet()) {
                        Volume volume = entry.getValue();
                        setOwnVolume(entry.getKey(), volume.left * eased, volume.right * eased);
                    }
                }
            }, step * RESTORE_FADE_STEP_MS);
        }
    }

    private void restoreNormalVolume(AudioTrack track) {
        if (track == null) return;
        Volume volume;
        synchronized (normalVolumes) {
            volume = normalVolumes.get(track);
        }
        if (volume == null) return;
        setOwnVolume(track, volume.left, volume.right);
    }

    private void setOwnVolume(AudioTrack track, float left, float right) {
        if (track == null) return;
        ownVolumeWrite.set(Boolean.TRUE);
        try {
            track.setStereoVolume(left, right);
        } catch (Throwable ignored) {
            try {
                track.setVolume(Math.max(left, right));
            } catch (Throwable ignoredAgain) {
                // The AudioTrack may have been released during the ad transition.
            }
        } finally {
            ownVolumeWrite.set(Boolean.FALSE);
        }
    }

    private static final class Volume {
        final float left;
        final float right;

        Volume(float left, float right) {
            this.left = left;
            this.right = right;
        }
    }
}
