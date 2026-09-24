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
import com.eza.spicyex.xposed.SpotifySymbolResolver;
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
    private volatile long mediaPositionMs = -1;
    private volatile long mediaPositionUpdatedAtElapsedMs = 0;
    private volatile long seekOverrideUntilElapsedMs = 0;
    private volatile WeakReference<MediaSession> currentMediaSession = new WeakReference<>(null);
    private Method playerWrapperGetStateMethod;

    void install(XpPackage lpparm, SpotifySymbolResolver symbols) {
        hookPlayerStateBridge(lpparm, symbols);
        installMediaSessionHook();
    }

    private void hookPlayerStateBridge(XpPackage lpparm, SpotifySymbolResolver symbols) {
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
                                XpLog.log(NativeSpicyLyricsHook.TAG
                                        + " state update listener failed: " + t);
                            }
                        }
                    });
            XpLog.log(NativeSpicyLyricsHook.TAG + " player state builder hook installed");
        } catch (Throwable t) {
            XpLog.log(NativeSpicyLyricsHook.TAG + " player state builder hook failed: " + t);
        }

        try {
            playerWrapperGetStateMethod = symbols.cache.method("playback.wrapper.getState", () -> {
                var bridge = symbols.dexKit();
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

                return bridge.findMethod(FindMethod.create()
                                .searchInClass(stateWrapperClasses)
                                .matcher(MethodMatcher.create().name("getState")))
                        .get(0)
                        .getMethodInstance(lpparm.classLoader());
            });
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
            XpHooks.findAfter(MediaSession.class, "setMetadata", "artwork:MediaSession#setMetadata",
                    param -> com.eza.spicyex.lyrics.SpotifyArtworkCache.capture(
                            (android.media.MediaMetadata) param.args[0]), android.media.MediaMetadata.class);
        } catch (Throwable t) {
            XpLog.log(NativeSpicyLyricsHook.TAG + " artwork metadata hook unavailable: " + t.getClass().getSimpleName());
        }
        try {
            XpHooks.findAfter(MediaSession.class, "setPlaybackState",
                    "playback:MediaSession#setPlaybackState", param -> {
                        currentMediaSession = new WeakReference<>((MediaSession) param.thisObject);
                        PlaybackState playbackState = (PlaybackState) param.args[0];
                        if (playbackState == null) return;
                        isPlaying = playbackState.getState() == PlaybackState.STATE_PLAYING;
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

    /** Same capability-checked posture as skipToNext/PreviousTrack: false, and no transport call
     *  sent at all, when the current PlaybackState doesn't currently advertise ACTION_SEEK_TO
     *  (most visibly a free-account session with seek/skip restricted) - callers can use this to
     *  proactively hide/disable seek affordances instead of firing a seek that gets silently
     *  ignored server-side, which used to be the only way this surfaced. */
    boolean seekSpotifyTo(long positionMs) {
        boolean ok = sendTransportControl("seek", PlaybackState.ACTION_SEEK_TO,
                tc -> tc.seekTo(positionMs));
        if (ok) forcePosition(positionMs);
        return ok;
    }

    /** Whether the current PlaybackState advertises ACTION_SEEK_TO right now - see
     *  {@link #seekSpotifyTo}. False whenever no session is captured yet, same as every other
     *  capability check here. */
    // canSeek() is polled from the lyrics frame loop; each read is a binder call into
    // system_server, so it is re-read at most every CAN_SEEK_TTL_MS (still fast enough to catch
    // an ad starting).
    private static final long CAN_SEEK_TTL_MS = 400L;
    private long canSeekReadAt = Long.MIN_VALUE / 2;
    private boolean canSeekCached;

    boolean canSeek() {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - canSeekReadAt < CAN_SEEK_TTL_MS) return canSeekCached;
        canSeekReadAt = now;
        MediaController controller = transportController();
        PlaybackState state = controller == null ? null : controller.getPlaybackState();
        canSeekCached = state != null && (state.getActions() & PlaybackState.ACTION_SEEK_TO) != 0;
        return canSeekCached;
    }

    /** Toggles play/pause through Spotify's own MediaSession transport. Null-safe: false when
     * no session is captured yet (same failure posture as seek). */
    boolean togglePlayPause() {
        try {
            MediaController controller = transportController();
            if (controller == null) return false;
            if (isPlayerActuallyPlaying()) controller.getTransportControls().pause();
            else controller.getTransportControls().play();
            return true;
        } catch (Throwable t) {
            XpLog.log(NativeSpicyLyricsHook.TAG + " media toggle failed: " + t);
            return false;
        }
    }

    /** Skips to the next/previous track through the same transport. False when unavailable. */
    boolean skipToNextTrack() {
        return sendTransportControl("next", PlaybackState.ACTION_SKIP_TO_NEXT, tc -> tc.skipToNext());
    }

    boolean skipToPreviousTrack() {
        return sendTransportControl("previous", PlaybackState.ACTION_SKIP_TO_PREVIOUS,
                tc -> tc.skipToPrevious());
    }

    boolean toggleSpotifySaved(String mode, SpotifyTrack expected,
                               java.util.function.Supplier<SpotifyTrack> currentTrack) {
        return SpotifyCollectionAction.dispatch(mode, expected, currentTrack, this::collectionSession);
    }

    private SpotifyCollectionAction.Session collectionSession() {
        MediaController controller = transportController();
        if (controller == null) return null;
        return new SpotifyCollectionAction.Session() {
            @Override public String packageName() {
                return controller.getPackageName();
            }

            @Override public String trackUri() {
                android.media.MediaMetadata metadata = controller.getMetadata();
                return metadata == null ? null
                        : metadata.getString(android.media.MediaMetadata.METADATA_KEY_MEDIA_ID);
            }

            @Override public java.util.List<SpotifyCollectionAction.AdvertisedAction> advertisedActions() {
                PlaybackState state = controller.getPlaybackState();
                java.util.List<SpotifyCollectionAction.AdvertisedAction> actions = new ArrayList<>();
                if (state != null && state.getCustomActions() != null) {
                    for (PlaybackState.CustomAction action : state.getCustomActions()) {
                        if (action == null || action.getAction() == null) continue;
                        CharSequence label = action.getName();
                        actions.add(new SpotifyCollectionAction.AdvertisedAction(
                                action.getAction(), label == null ? null : label.toString()));
                    }
                }
                return actions;
            }

            @Override public boolean dispatch(String id) {
                MediaSession session = currentMediaSession.get();
                if (session == null || !session.getSessionToken().equals(controller.getSessionToken())) return false;
                PlaybackState state = controller.getPlaybackState();
                if (state == null || state.getCustomActions() == null) return false;
                for (PlaybackState.CustomAction action : state.getCustomActions()) {
                    if (action != null && id.equals(action.getAction())) {
                        controller.getTransportControls().sendCustomAction(id, action.getExtras());
                        return true;
                    }
                }
                return false;
            }
        };
    }

    private interface TransportControlCall {
        void send(MediaController.TransportControls controls);
    }

    private boolean sendTransportControl(String name, long requiredAction, TransportControlCall call) {
        try {
            MediaController controller = transportController();
            if (controller == null) return false;
            PlaybackState state = controller.getPlaybackState();
            if (state == null || (state.getActions() & requiredAction) == 0) return false;
            call.send(controller.getTransportControls());
            return true;
        } catch (Throwable t) {
            XpLog.log(NativeSpicyLyricsHook.TAG + " media " + name + " failed: " + t);
            return false;
        }
    }

    private MediaController transportController() {
        MediaSession session = currentMediaSession == null ? null : currentMediaSession.get();
        if (session == null) return null;
        MediaController controller = session.getController();
        if (controller == null || controller.getTransportControls() == null) return null;
        return controller;
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

    private static final String[] PAUSED_ACCESSOR_NAMES = {"isPaused", "paused"};
    private static final Method[] NO_PAUSED_ACCESSORS = new Method[0];
    /** Resolved once per PlayerState class - see {@link #pausedAccessors}. */
    private Class<?> pausedAccessorOwner;
    private Method[] pausedAccessorCache;

    /**
     * Spotify's own paused accessors, resolved reflectively but only once per PlayerState class.
     *
     * <p>This is polled from the lyric screen's vsync callback, so it runs on every frame the
     * screen is up. Resolving it through the generic reflection facade each time walked the whole
     * (obfuscated, method-heavy) class hierarchy calling {@code getDeclaredMethods} - which
     * allocates a fresh array per class on ART - and then threw and caught a
     * {@code NoSuchMethodError}, stack trace and all, for whichever of the two spellings this build
     * does not have. Milliseconds of pure overhead per frame on a slow device, spent entirely on
     * re-deriving an answer that cannot change while the class does not. The empty result is cached
     * too, so a build with neither spelling does not re-pay the miss every frame either.
     */
    private Method[] pausedAccessors(Class<?> stateClass) {
        if (stateClass == pausedAccessorOwner && pausedAccessorCache != null) {
            return pausedAccessorCache;
        }
        pausedAccessorOwner = stateClass;
        pausedAccessorCache = NO_PAUSED_ACCESSORS;
        ArrayList<Method> found = new ArrayList<>(PAUSED_ACCESSOR_NAMES.length);
        for (String name : PAUSED_ACCESSOR_NAMES) {
            for (Class<?> current = stateClass; current != null && current != Object.class;
                    current = current.getSuperclass()) {
                try {
                    Method candidate = current.getDeclaredMethod(name);
                    if (Modifier.isStatic(candidate.getModifiers())) continue;
                    candidate.setAccessible(true);
                    found.add(candidate);
                    break;
                } catch (NoSuchMethodException | RuntimeException ignored) {
                }
            }
        }
        if (!found.isEmpty()) pausedAccessorCache = found.toArray(new Method[0]);
        return pausedAccessorCache;
    }

    /** True only when Spotify's own PlayerState says paused. Unlike isPlayerActuallyPlaying this
     *  ignores the media session, which does not always report ads as playing. */
    boolean isPlayerStatePaused() {
        try {
            Object state = References.playerState == null ? null : References.playerState.get();
            if (state == null) return false;
            for (Method paused : pausedAccessors(state.getClass())) {
                try {
                    Object result = paused.invoke(state);
                    if (result instanceof Boolean && (Boolean) result) return true;
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    boolean isPlayerActuallyPlaying() {
        if (!isPlaying) return false;
        try {
            Object state = References.playerState == null ? null : References.playerState.get();
            if (state != null) {
                for (Method paused : pausedAccessors(state.getClass())) {
                    try {
                        Object result = paused.invoke(state);
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
