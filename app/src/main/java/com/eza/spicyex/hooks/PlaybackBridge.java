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

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
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

    private volatile boolean isPlaying;
    private volatile long mediaPositionMs = -1;
    private volatile long mediaPositionUpdatedAtElapsedMs = 0;
    private volatile long seekOverrideUntilElapsedMs = 0;
    private volatile WeakReference<MediaSession> currentMediaSession = new WeakReference<>(null);
    private Method playerWrapperGetStateMethod;

    void install(XC_LoadPackage.LoadPackageParam lpparm, DexKitBridge bridge) {
        hookPlayerStateBridge(lpparm, bridge);
        installMediaSessionHook();
    }

    private void hookPlayerStateBridge(XC_LoadPackage.LoadPackageParam lpparm, DexKitBridge bridge) {
        NativeSpicyLyricsHook.dbgEnter("hookPlayerStateBridge");
        try {
            XposedHelpers.findAndHookMethod(
                    "com.spotify.player.model.AutoValue_PlayerState$Builder",
                    lpparm.classLoader,
                    "build",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Object state = param.getResult();
                            if (state == null) return;
                            References.playerState = new WeakReference<>(state);
                        }
                    });
            XposedBridge.log(NativeSpicyLyricsHook.TAG + " player state builder hook installed");
        } catch (Throwable t) {
            XposedBridge.log(NativeSpicyLyricsHook.TAG + " player state builder hook failed: " + t);
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
                    .getMethodInstance(lpparm.classLoader);
            XposedBridge.hookMethod(playerWrapperGetStateMethod, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    References.playerStateWrapper = new WeakReference<>(param.thisObject);
                }
            });
            XposedBridge.log(NativeSpicyLyricsHook.TAG + " player wrapper getState hook installed");
        } catch (Throwable t) {
            XposedBridge.log(NativeSpicyLyricsHook.TAG + " player wrapper getState hook failed: " + t);
        }
    }

    private void installMediaSessionHook() {
        try {
            XposedHelpers.findAndHookMethod(MediaSession.class, "setPlaybackState", PlaybackState.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    currentMediaSession = new WeakReference<>((MediaSession) param.thisObject);
                    PlaybackState playbackState = (PlaybackState) param.args[0];
                    if (playbackState == null) return;
                    isPlaying = playbackState.getState() == PlaybackState.STATE_PLAYING;
                    long position = playbackState.getPosition();
                    if (position >= 0) {
                        mediaPositionMs = position;
                        mediaPositionUpdatedAtElapsedMs = SystemClock.elapsedRealtime();
                    }
                }
            });
            XposedBridge.log(NativeSpicyLyricsHook.TAG + " MediaSession playback hook installed");
        } catch (Throwable t) {
            XposedBridge.log(NativeSpicyLyricsHook.TAG + " MediaSession playback hook failed: " + t);
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
            XposedBridge.log(NativeSpicyLyricsHook.TAG + " media seek failed: " + t);
            return false;
        }
    }

    long readBestMeasuredProgressMs(SpotifyTrack track, boolean playing) {
        long now = SystemClock.elapsedRealtime();
        long media = mediaPositionMs;
        if (media >= 0 && now < seekOverrideUntilElapsedMs) {
            if (playing && mediaPositionUpdatedAtElapsedMs > 0) {
                return Math.max(0, media + (now - mediaPositionUpdatedAtElapsedMs));
            }
            return Math.max(0, media);
        }

        long playerStateProgress = readPlayerStateProgressMs(playing);
        if (playerStateProgress >= 0) return playerStateProgress;

        if (track != null && track.position >= 0) {
            long wallNow = System.currentTimeMillis();
            if (!playing && track.lastUpdated > 0) {
                long advancedBy = Math.max(0, wallNow - track.lastUpdated);
                return Math.max(0, track.position - advancedBy);
            }
            return Math.max(0, track.position);
        }

        if (media >= 0) {
            if (playing && mediaPositionUpdatedAtElapsedMs > 0) {
                return Math.max(0, media + (now - mediaPositionUpdatedAtElapsedMs));
            }
            return Math.max(0, media);
        }
        return -1;
    }

    boolean isPlayerActuallyPlaying() {
        if (!isPlaying) return false;
        try {
            Object state = References.playerState == null ? null : References.playerState.get();
            if (state != null) {
                for (String method : new String[]{"isPaused", "paused"}) {
                    try {
                        Object result = XposedHelpers.callMethod(state, method);
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
            Object posOpt = XposedHelpers.callMethod(state, "positionAsOfTimestamp");
            if (posOpt == null) return -1;
            Matcher matcher = DIGITS.matcher(posOpt.toString());
            if (!matcher.find()) return -1;
            long basePos = Long.parseLong(matcher.group());
            long timestamp = 0;
            try {
                Object rawTimestamp = XposedHelpers.callMethod(state, "timestamp");
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
