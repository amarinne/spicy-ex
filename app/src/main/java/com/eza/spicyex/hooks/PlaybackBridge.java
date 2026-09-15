package com.eza.spicyex.hooks;

import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.SystemClock;

import com.eza.spicyex.References;
import com.eza.spicyex.SpotifyTrack;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.eza.spicyex.xposed.XpHooks;
import com.eza.spicyex.xposed.XpLog;
import com.eza.spicyex.xposed.XpPackage;
import com.eza.spicyex.xposed.XpReflect;
import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.FieldMatcher;
import org.luckypray.dexkit.query.matchers.FieldsMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;

/** Bridges Spotify playback/session state into renderer-friendly progress and seek operations. */
final class PlaybackBridge {
    private static final Pattern DIGITS = Pattern.compile("\\d+");

    /** Fired synchronously, right after References.playerState/playerStateStrong are updated,
     *  every time Spotify's own state machine builds a new PlayerState - e.g. AdMuteController
     *  uses this for near-instant ad-track detection instead of a slower poll. */
    private static volatile Runnable stateUpdateListener;

    static void setStateUpdateListener(Runnable listener) {
        stateUpdateListener = listener;
    }

    private volatile boolean isPlaying;
    private volatile long currentActions = -1;
    private volatile long mediaPositionMs = -1;
    private volatile long mediaPositionUpdatedAtElapsedMs = 0;
    private volatile long seekOverrideUntilElapsedMs = 0;
    private volatile WeakReference<MediaSession> currentMediaSession = new WeakReference<>(null);
    private Method playerWrapperGetStateMethod;

    void install(XpPackage lpparm, DexKitBridge bridge) {
        hookPlayerStateBridge(lpparm, bridge);
        installMediaSessionHook();
    }

    private void hookPlayerStateBridge(XpPackage lpparm, DexKitBridge bridge) {
        NativeSpicyLyricsHook.dbgEnter("hookPlayerStateBridge");
        try {
            XpHooks.findAfter(
                    "com.spotify.player.model.AutoValue_PlayerState$Builder",
                    lpparm.classLoader(),
                    "build",
                    "playback:PlayerStateBuilder#build",
                    param -> {
                        Object state = param.getResult();
                        if (state == null) return;
                        References.playerStateStrong = state;
                        References.playerState = new WeakReference<>(state);
                        Runnable listener = stateUpdateListener;
                        if (listener != null) {
                            try {
                                listener.run();
                            } catch (Throwable t) {
                                XpLog.log(NativeSpicyLyricsHook.TAG + " state update listener failed: " + t);
                            }
                        }
                    });
            XpLog.log(NativeSpicyLyricsHook.TAG + " player state builder hook installed");
        } catch (Throwable t) {
            XpLog.log(NativeSpicyLyricsHook.TAG + " player state builder hook failed: " + t);
        }

        try {
            var stateWrapperClasses = bridge.findClass(FindClass.create().matcher(
                    ClassMatcher.create()
                            .modifiers(Modifier.PUBLIC | Modifier.FINAL)
                            .interfaceCount(1)
                            .fields(FieldsMatcher.create()
                                    .add(FieldMatcher.create().modifiers(Modifier.PUBLIC | Modifier.FINAL))
                                    .add(FieldMatcher.create()
                                            .modifiers(Modifier.PUBLIC | Modifier.FINAL).type(String.class))
                                    .add(FieldMatcher.create()
                                            .modifiers(Modifier.PUBLIC | Modifier.FINAL).type(ArrayList.class))
                                    .add(FieldMatcher.create().modifiers(Modifier.PUBLIC).type(Object.class))
                                    .add(FieldMatcher.create().modifiers(Modifier.PUBLIC).type(Bundle.class))
                            )));

            playerWrapperGetStateMethod = bridge.findMethod(FindMethod.create()
                            .searchInClass(stateWrapperClasses)
                            .matcher(MethodMatcher.create().name("getState")))
                    .get(0)
                    .getMethodInstance(lpparm.classLoader());
            XpHooks.hookAfter(playerWrapperGetStateMethod, "playback:PlayerWrapper#getState", param -> {
                References.playerStateWrapperStrong = param.thisObject;
                References.playerStateWrapper = new WeakReference<>(param.thisObject);
            });
            XpLog.log(NativeSpicyLyricsHook.TAG + " player wrapper getState hook installed");
        } catch (Throwable t) {
            XpLog.log(NativeSpicyLyricsHook.TAG + " player wrapper getState hook failed: " + t);
        }
    }

    private void installMediaSessionHook() {
        try {
            XpHooks.findAfter(MediaSession.class, "setPlaybackState",
                    "playback:MediaSession#setPlaybackState", param -> {
                        currentMediaSession = new WeakReference<>((MediaSession) param.thisObject);
                        PlaybackState playbackState = (PlaybackState) param.args[0];
                        if (playbackState == null) return;
                        isPlaying = playbackState.getState() == PlaybackState.STATE_PLAYING;
                        currentActions = playbackState.getActions();
                        long position = playbackState.getPosition();
                        if (position >= 0) {
                            mediaPositionMs = position;
                            mediaPositionUpdatedAtElapsedMs = SystemClock.elapsedRealtime();
                        }
                    }, PlaybackState.class);
            XpLog.log(NativeSpicyLyricsHook.TAG + " MediaSession playback hook installed");
        } catch (Throwable t) {
            XpLog.log(NativeSpicyLyricsHook.TAG + " MediaSession playback hook failed: " + t);
        }
    }

    boolean seekSpotifyTo(long positionMs) {
        try {
            MediaSession session = currentMediaSession == null ? null : currentMediaSession.get();
            if (session == null) return false;
            MediaController controller = session.getController();
            if (controller == null || controller.getTransportControls() == null) return false;
            controller.getTransportControls().seekTo(positionMs);
            forcePosition(positionMs);
            return true;
        } catch (Throwable t) {
            XpLog.log(NativeSpicyLyricsHook.TAG + " media seek failed: " + t);
            return false;
        }
    }

    boolean togglePlayback() {
        try {
            MediaSession session = currentMediaSession == null ? null : currentMediaSession.get();
            if (session == null) return false;
            MediaController controller = session.getController();
            if (controller == null || controller.getTransportControls() == null) return false;
            if (isPlaying) controller.getTransportControls().pause();
            else controller.getTransportControls().play();
            return true;
        } catch (Throwable t) {
            XpLog.log(NativeSpicyLyricsHook.TAG + " toggle playback failed: " + t);
            return false;
        }
    }

    boolean skipToNextTrack() {
        try {
            MediaSession session = currentMediaSession == null ? null : currentMediaSession.get();
            if (session == null) return false;
            MediaController controller = session.getController();
            if (controller == null || controller.getTransportControls() == null) return false;
            controller.getTransportControls().skipToNext();
            return true;
        } catch (Throwable t) {
            XpLog.log(NativeSpicyLyricsHook.TAG + " skip to next failed: " + t);
            return false;
        }
    }

    boolean toggleSpotifySaved() {
        try {
            MediaSession session = currentMediaSession == null ? null : currentMediaSession.get();
            if (session == null) return false;
            MediaController controller = session.getController();
            if (controller == null || controller.getTransportControls() == null) return false;
            PlaybackState state = controller.getPlaybackState();
            if (state == null || state.getCustomActions() == null) return false;
            for (PlaybackState.CustomAction action : state.getCustomActions()) {
                CharSequence label = action.getName();
                if (label == null || !label.toString().toLowerCase(java.util.Locale.ROOT).contains("collection")) continue;
                controller.getTransportControls().sendCustomAction(action.getAction(), action.getExtras());
                return true;
            }
            return false;
        } catch (Throwable t) {
            XpLog.log(NativeSpicyLyricsHook.TAG + " toggle saved failed: " + t);
            return false;
        }
    }

    long readBestMeasuredProgressMs(SpotifyTrack track, boolean playing) {
        return getCurrentPositionMs(track, playing);
    }

    /** Live position of the track Spotify is actually playing right now, in ms.
     *  Source priority: seek-forced value (1800ms after a seek) -> live MediaController query
     *  (fresh every call, extrapolated with update time + speed) -> PlayerState reflection ->
     *  track snapshot (extrapolated only while playing) -> cached MediaSession value. */
    long getCurrentPositionMs(SpotifyTrack track, boolean playing) {
        long now = SystemClock.elapsedRealtime();
        long media = mediaPositionMs;
        if (media >= 0 && now < seekOverrideUntilElapsedMs) {
            return extrapolate(media, mediaPositionUpdatedAtElapsedMs, playing, 1f, now);
        }

        long live = readLiveControllerPositionMs(playing);
        if (live >= 0) return live;

        long playerStateProgress = readPlayerStateProgressMs(playing);
        if (playerStateProgress >= 0) return playerStateProgress;

        long fromTrack = readTrackPositionMs(track, playing);
        if (fromTrack >= 0) return fromTrack;

        if (media >= 0) {
            return extrapolate(media, mediaPositionUpdatedAtElapsedMs, playing, 1f, now);
        }
        return -1;
    }

    private long readLiveControllerPositionMs(boolean playing) {
        try {
            MediaSession session = currentMediaSession == null ? null : currentMediaSession.get();
            if (session == null) return -1;
            MediaController controller = session.getController();
            if (controller == null) return -1;
            PlaybackState state = controller.getPlaybackState();
            if (state == null) return -1;
            long position = state.getPosition();
            if (position < 0) return -1;
            boolean effectivelyPlaying = state.getState() == PlaybackState.STATE_PLAYING && playing;
            return extrapolate(position, state.getLastPositionUpdateTime(),
                    effectivelyPlaying, state.getPlaybackSpeed(), SystemClock.elapsedRealtime());
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static long extrapolate(long baseMs, long baseAtElapsedMs, boolean playing,
            float speed, long nowElapsedMs) {
        if (baseMs < 0) return -1;
        if (!playing || baseAtElapsedMs <= 0) return Math.max(0, baseMs);
        float rate = speed > 0 ? speed : 1f;
        return Math.max(0, baseMs + (long) ((nowElapsedMs - baseAtElapsedMs) * rate));
    }

    private static long readTrackPositionMs(SpotifyTrack track, boolean playing) {
        if (track == null || track.position < 0) return -1;
        if (!playing || track.lastUpdated <= 0) return Math.max(0, track.position);
        return Math.max(0, track.position + Math.max(0, System.currentTimeMillis() - track.lastUpdated));
    }

    /** True while readBestMeasuredProgressMs() is still returning the optimistic forced value from
     *  a recent seek instead of genuine backend-reported state - a caller trying to verify a seek
     *  actually took effect must wait this out first, or it will only ever read its own write. */
    boolean isSeekOverrideActive() {
        return SystemClock.elapsedRealtime() < seekOverrideUntilElapsedMs;
    }

    boolean canSeek() {
        return currentActions < 0 || (currentActions & PlaybackState.ACTION_SEEK_TO) != 0;
    }

    boolean canSkipToNext() {
        return currentActions < 0 || (currentActions & PlaybackState.ACTION_SKIP_TO_NEXT) != 0;
    }

    boolean isPlayerActuallyPlaying() {
        if (!isPlaying) return false;
        try {
            Object state = References.playerState == null ? null : References.playerState.get();
            if (state != null) {
                for (String method : new String[]{"isPaused", "paused"}) {
                    try {
                        Object result = XpReflect.callMethod(state, method);
                        if (result instanceof Boolean && (Boolean) result) return false;
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return true;
    }

    private void forcePosition(long positionMs) {
        mediaPositionMs = Math.max(0, positionMs);
        mediaPositionUpdatedAtElapsedMs = SystemClock.elapsedRealtime();
        seekOverrideUntilElapsedMs = SystemClock.elapsedRealtime() + 1800;
    }

    private long readPlayerStateProgressMs(boolean playing) {
        try {
            Object state = References.playerState == null ? null : References.playerState.get();
            if (state == null) return -1;
            Object posOpt = XpReflect.callMethod(state, "positionAsOfTimestamp");
            if (posOpt == null) return -1;
            Matcher matcher = DIGITS.matcher(posOpt.toString());
            if (!matcher.find()) return -1;
            long basePos = Long.parseLong(matcher.group());
            long timestamp = 0;
            try {
                Object rawTimestamp = XpReflect.callMethod(state, "timestamp");
                if (rawTimestamp instanceof Long) timestamp = (Long) rawTimestamp;
            } catch (Throwable ignored) {
            }
            if (!playing || timestamp <= 0) return Math.max(0, basePos);
            return Math.max(0, basePos + (System.currentTimeMillis() - timestamp));
        } catch (Throwable ignored) {
            return -1;
        }
    }
}
