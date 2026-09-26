package com.eza.spicyex.hooks;

import static com.eza.spicyex.hooks.NativeLyricsUtils.safe;

import android.app.Activity;
import android.app.Application;
import android.content.Context;

import com.eza.spicyex.BuildStamp;
import com.eza.spicyex.Diagnostics;
import com.eza.spicyex.References;
import com.eza.spicyex.SpotifyTrack;
import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.lyrics.CacheClearKind;
import com.eza.spicyex.lyrics.session.AIPaidArtifactCache;

import com.eza.spicyex.xposed.XpLog;
import com.eza.spicyex.xposed.XpReflect;

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
    private final Context applicationContext;
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
    private final LyricsSessionManager lyricsSessionManager;
    private SpicyLyricBridgeCoordinator bridgeCoordinator;

    public NativeSpicyLyricsHook(Context context) {
        Context app = context.getApplicationContext();
        applicationContext = app != null ? app : context;
        lyricsSessionManager = new LyricsSessionManager(this, lyricsFetchCoordinator, applicationContext);
    }

    static void dbg(String function, String message) {
        if (!DEBUG_LOGGING) return;
        XpLog.log(TAG + " [" + BUILD_CLUE + "] " + function + "() " + safe(message));
    }

    static void dbgEnter(String function) {
        dbg(function, "enter");
    }

    @Override
    protected void hook() {
        Diagnostics.event("bootstrap", "hook_start",
                Diagnostics.context("process", processName()));
        dbg("hook", "native Spicy renderer hook enabled version=" + BuildStamp.FULL);
        new NativeLyricsCaptureHook(
                lpparm.classLoader(),
                symbols,
                lyricsFetchCoordinator.nativeLyricsSource(),
                this::getCurrentTrackSafely
        ).hook();
        playbackBridge.install(lpparm, symbols);
        activityTakeoverHook.hook();
        String processName = processName();
        XpLog.log(TAG + " bridge init package=" + lpparm.packageName()
                + " appProcess=" + processName);
        if (lpparm.packageName().equals(processName)) {
            // At process start, not only on fullscreen open: a user who never opens the fullscreen
            // screen would otherwise keep derived data from a retired cache epoch indefinitely.
            DeployCacheCleaner.ensureCleared(applicationContext);
            // Transition B515 paid records before any AI lane can dispatch. This only opens local
            // storage; it performs no provider request and leaves the source XML untouched.
            AIPaidArtifactCache.prepare(applicationContext);
            lyricsSessionManager.start();
            bridgeCoordinator = new SpicyLyricBridgeCoordinator(
                    lyricsSessionManager, applicationContext);
            bridgeCoordinator.start();
            // Ad muting runs process-wide, not per screen: it applies to local playback
            // everywhere while changing only Spotify's ad AudioTrack.
            new AdMuteController(this, applicationContext).start();
            ActivityResultBridge.install();
            Diagnostics.event("bootstrap", "hook_ready",
                    Diagnostics.context("result", "main_process"));
        } else {
            XpLog.log(TAG + " bridge skipped outside main Spotify process");
            Diagnostics.event("bootstrap", "hook_ready",
                    Diagnostics.context("result", "secondary_process"));
        }
    }

    private static String processName() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            return Application.getProcessName();
        }
        return "unknown";
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
        Activity activity = References.currentActivity();
        if (activity != null) return activity.getApplicationContext();
        try {
            Object app = XpReflect.callStaticMethod(
                    XpReflect.findClass("android.app.ActivityThread", null), "currentApplication");
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
            return References.getTrackTitle(lpparm.classLoader(), symbols);
        } catch (Throwable t) {
            XpLog.log(TAG + " track read failed: " + t);
            return null;
        }
    }

    public boolean seekSpotifyTo(long positionMs) {
        return playbackBridge.seekSpotifyTo(positionMs);
    }

    @Override
    public boolean canSeek() {
        return playbackBridge.canSeek();
    }

    @Override
    public boolean togglePlayPause() {
        return playbackBridge.togglePlayPause();
    }

    @Override
    public boolean skipToNextTrack() {
        return playbackBridge.skipToNextTrack();
    }

    @Override
    public boolean skipToPreviousTrack() {
        return playbackBridge.skipToPreviousTrack();
    }

    @Override
    public boolean toggleSpotifySaved(String mode, com.eza.spicyex.SpotifyTrack expected) {
        return playbackBridge.toggleSpotifySaved(mode, expected, this::getCurrentTrackSafely);
    }

    public long readBestMeasuredProgressMs(SpotifyTrack track, boolean playing) {
        return playbackBridge.readBestMeasuredProgressMs(track, playing);
    }

    public boolean isPlayerActuallyPlaying() {
        return playbackBridge.isPlayerActuallyPlaying();
    }

    boolean isPlayerStatePaused() {
        return playbackBridge.isPlayerStatePaused();
    }

    @Override
    public LyricsSessionManager.SessionSubscription subscribeLyricsSession(
            LyricsSessionManager.Listener listener) {
        return lyricsSessionManager.subscribe(listener);
    }

    @Override
    public LyricsSessionManager.LyricsRequest fetchLyrics(
            SpotifyTrack track, LyricsResultCallback callback) {
        return lyricsSessionManager.requestLyrics(track, callback);
    }

    @Override
    public void refreshLyricsLayer(com.eza.spicyex.lyrics.session.LayerKind layer) {
        lyricsSessionManager.refreshLayer(layer);
    }

    @Override
    public com.eza.spicyex.lyrics.ai.AiRequestStartResult requestAiLyricsLayer(
            com.eza.spicyex.lyrics.session.LayerKind layer) {
        return lyricsSessionManager.requestAiLayer(layer);
    }

    @Override
    public void restoreLyricsLayer(com.eza.spicyex.lyrics.session.LayerKind layer) {
        lyricsSessionManager.restoreLayer(layer);
    }

    @Override
    public void clearLyricsCache(CacheClearKind kind) {
        lyricsSessionManager.clearCache(kind);
    }


    interface LyricsResultCallback {
        void onSuccess(LyricsDocument document);
        void onError(String error);
    }

}
