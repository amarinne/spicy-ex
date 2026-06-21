package com.eza.spicyex.hooks;

import static com.eza.spicyex.hooks.NativeLyricsUtils.safe;

import android.app.Activity;
import android.content.Context;

import com.eza.spicyex.BuildStamp;
import com.eza.spicyex.References;
import com.eza.spicyex.SpotifyTrack;
import com.eza.spicyex.lyrics.LyricsDocument;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Native Spicy shell.
 *
 * Current alpha scope:
 * - mount Android-native renderer root in Spotify fullscreen lyrics activity,
 * - bridge Spotify track/progress/play state,
 * - fetch Spicy Lyrics API response with existing Spotify auth capture,
 * - render static/line/syllable payloads as native line-synced lyrics,
 * - show romanized secondary lines using Spicy fork processing ports.
 */
public class NativeSpicyLyricsHook extends SpotifyHook implements LyricsHost {
    static final String TAG = "[SpotifyPlusSpicy]";
    private static final String BUILD_CLUE = BuildStamp.CLUE;
    private static final boolean DEBUG_LOGGING = false;
    static volatile int fetchGeneration;
    private final NowPlayingInjector nowPlayingInjector = new NowPlayingInjector(this);
    private final LyricsActivityTakeoverHook activityTakeoverHook =
            new LyricsActivityTakeoverHook(this, nowPlayingInjector);
    private final PlaybackBridge playbackBridge = new PlaybackBridge();
    private final LyricsFetchCoordinator lyricsFetchCoordinator =
            new LyricsFetchCoordinator(
                    NativeRuntime.HTTP,
                    NativeSpicyLyricsHook::appContext,
                    NativeRuntime.GOOGLE_PROCESSING_VERSION
            );

    static void dbg(String function, String message) {
        if (!DEBUG_LOGGING) return;
        XposedBridge.log(TAG + " [" + BUILD_CLUE + "] " + function + "() " + safe(message));
    }

    static void dbgEnter(String function) {
        dbg(function, "enter");
    }

    @Override
    protected void hook() {
        dbg("hook", "native Spicy renderer hook enabled version=" + BuildStamp.FULL);
        new AuthTokenCaptureHook(lpparm.classLoader).hook();
        new NativeLyricsCaptureHook(
                lpparm.classLoader,
                bridge,
                lyricsFetchCoordinator.nativeLyricsSource(),
                this::getCurrentTrackSafely
        ).hook();
        playbackBridge.install(lpparm, bridge);
        activityTakeoverHook.hook();
    }

    public void markExplicitLyricsExit(Activity activity) {
        activityTakeoverHook.markExplicitLyricsExit(activity);
    }

    void launchNativeLyricsFullscreen(Activity activity) {
        activityTakeoverHook.launchNativeLyricsFullscreen(activity);
    }

    @Override
    public void markLyricsKeepAlive(Activity activity) {
        activityTakeoverHook.markLyricsActivityKeepWindow(activity);
    }

    private static Context appContext() {
        Activity activity = References.currentActivity;
        if (activity != null) return activity.getApplicationContext();
        try {
            Object app = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityThread", null), "currentApplication");
            if (app instanceof Context) return ((Context) app).getApplicationContext();
        } catch (Throwable ignored) {
        }
        return null;
    }

    boolean isNativeSpicyEnabled(Activity activity) {
        return activityTakeoverHook.isNativeSpicyEnabled(activity);
    }

    public SpotifyTrack getCurrentTrackSafely() {
        dbgEnter("getCurrentTrackSafely");
        try {
            if (References.playerState == null || References.playerState.get() == null) return null;
            return References.getTrackTitle(lpparm, bridge);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " track read failed: " + t);
            return null;
        }
    }

    public boolean seekSpotifyTo(long positionMs) {
        return playbackBridge.seekSpotifyTo(positionMs);
    }

    public long readBestMeasuredProgressMs(SpotifyTrack track, boolean playing) {
        return playbackBridge.readBestMeasuredProgressMs(track, playing);
    }

    public boolean isPlayerActuallyPlaying() {
        return playbackBridge.isPlayerActuallyPlaying();
    }

    public void fetchLyrics(Activity activity, SpotifyTrack track, int generation, LyricsResultCallback callback) {
        lyricsFetchCoordinator.fetchLyrics(activity, track, generation, callback);
    }


    interface LyricsResultCallback {
        void onSuccess(LyricsDocument document);
        void onError(String error);
    }

}
