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

import com.eza.spicyex.xposed.XpHooks;
import com.eza.spicyex.xposed.XpLog;
import com.eza.spicyex.xposed.XpPackage;
import com.eza.spicyex.xposed.SpotifySymbolResolver;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.FieldMatcher;
import org.luckypray.dexkit.query.matchers.FieldsMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;

/** Bridges Spotify playback/session state into renderer-friendly progress and seek operations. */
final class PlaybackBridge {
    private volatile boolean isPlaying;
    private volatile long mediaPositionMs = -1;
    private volatile long mediaPositionUpdatedAtElapsedMs = 0;
    private volatile long seekOverrideUntilElapsedMs = 0;
    private volatile WeakReference<MediaSession> currentMediaSession = new WeakReference<>(null);
    private Method playerWrapperGetStateMethod;

    private AudioOutputLatency outputLatency;

    void install(XpPackage lpparm, SpotifySymbolResolver symbols) {
        outputLatency = new AudioOutputLatency(lpparm.classLoader());
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
                    param -> com.eza.spicyex.lyrics.cache.SpotifyArtworkCache.capture(
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
                        // Buffering is playback on its way (around a seek, a track change), not
                        // a pause; the user's pause is PAUSED.
                        int s = playbackState.getState();
                        isPlaying = s == PlaybackState.STATE_PLAYING
                                || s == PlaybackState.STATE_BUFFERING;
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
            MediaController controller = transportController();
            if (controller == null) return false;
            // Check this controller's current capability before forcing the lyric position.
            PlaybackState state = controller.getPlaybackState();
            if (state == null || (state.getActions() & PlaybackState.ACTION_SEEK_TO) == 0) return false;
            controller.getTransportControls().seekTo(positionMs);
            forcePosition(positionMs);
            return true;
        } catch (Throwable t) {
            XpLog.log(NativeSpicyLyricsHook.TAG + " media seek failed: " + t);
            return false;
        }
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
        long now = SystemClock.uptimeMillis();
        if (now - canSeekReadAt < CAN_SEEK_TTL_MS) return canSeekCached;
        canSeekReadAt = now;
        try {
            MediaController controller = transportController();
            PlaybackState state = controller == null ? null : controller.getPlaybackState();
            canSeekCached = state != null && (state.getActions() & PlaybackState.ACTION_SEEK_TO) != 0;
        } catch (Throwable t) {
            canSeekCached = false;
        }
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

    /** The position being heard while Spotify plays locally: counted in the audio frames
     *  actually presented since the current PlayerState (AudioOutputLatency#heardPositionMs),
     *  or else what Spotify reports less the output path's latency. */
    long readBestMeasuredProgressMs(SpotifyTrack track, boolean playing) {
        reportedFromPlayerState = false;
        long reported = readReportedProgressMs(track, playing);
        if (reported <= 0 || !playing || outputLatency == null) return reported;
        if (reportedFromPlayerState && !parsedBuffering && Math.abs(parsedSpeed - 1d) < 0.001d) {
            long heard = outputLatency.heardPositionMs(
                    parsedState, parsedBasePos, parsedTimestamp, reported);
            if (heard >= 0) return heard;
        }
        return Math.max(0, reported - outputLatency.latencyMs());
    }

    private boolean reportedFromPlayerState;

    private long readReportedProgressMs(SpotifyTrack track, boolean playing) {
        long now = SystemClock.elapsedRealtime();
        long media = mediaPositionMs;
        if (media >= 0 && now < seekOverrideUntilElapsedMs) {
            if (playing && mediaPositionUpdatedAtElapsedMs > 0) {
                return Math.max(0, media + (now - mediaPositionUpdatedAtElapsedMs));
            }
            return Math.max(0, media);
        }

        long playerStateProgress = readPlayerStateProgressMs(playing);
        if (playerStateProgress >= 0) {
            reportedFromPlayerState = parsedTimestamp > 0;
            return playerStateProgress;
        }

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
    /** PlayerState's "a track is playing" flag (true while paused too; paused is separate). */
    private static final String[] PLAYING_ACCESSOR_NAMES = {"isPlaying", "playing"};
    private Class<?> playingAccessorOwner;
    private Method[] playingAccessorCache;
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
        pausedAccessorCache = booleanAccessors(stateClass, PAUSED_ACCESSOR_NAMES);
        return pausedAccessorCache;
    }

    /** Same one-time resolution as {@link #pausedAccessors}, for the playing flag. */
    private Method[] playingAccessors(Class<?> stateClass) {
        if (stateClass == playingAccessorOwner && playingAccessorCache != null) {
            return playingAccessorCache;
        }
        playingAccessorOwner = stateClass;
        playingAccessorCache = booleanAccessors(stateClass, PLAYING_ACCESSOR_NAMES);
        return playingAccessorCache;
    }

    private static Method[] booleanAccessors(Class<?> stateClass, String[] names) {
        ArrayList<Method> found = new ArrayList<>(names.length);
        for (String name : names) {
            for (Class<?> current = stateClass; current != null && current != Object.class;
                    current = current.getSuperclass()) {
                try {
                    Method candidate = current.getDeclaredMethod(name);
                    if (Modifier.isStatic(candidate.getModifiers())) continue;
                    Class<?> type = candidate.getReturnType();
                    if (type != boolean.class && type != Boolean.class) continue;
                    candidate.setAccessible(true);
                    found.add(candidate);
                    break;
                } catch (NoSuchMethodException | RuntimeException ignored) {
                }
            }
        }
        return found.isEmpty() ? NO_PAUSED_ACCESSORS : found.toArray(new Method[0]);
    }


    /**
     * Spotify's own PlayerState decides when it can be read: playing and not paused. The media
     * session is only the fallback. Its state is PLAYING alone, so the BUFFERING it posts around a
     * seek - and keeps posting after a seek Spotify rejected, while the song plays on - read as
     * paused: the screen showed paused and the lyrics stopped during playback.
     */
    boolean isPlayerActuallyPlaying() {
        try {
            Object state = References.playerState == null ? null : References.playerState.get();
            if (state != null) {
                Boolean fromState = playerStatePlaying(state, isPlaying);
                if (fromState != null) return fromState;
            }
        } catch (Throwable ignored) {
        }
        return isPlaying;
    }

    /** Playing per the PlayerState's own accessors; null when this build exposes neither. */
    Boolean playerStatePlaying(Object state, boolean sessionPlaying) {
        Method[] paused = pausedAccessors(state.getClass());
        Method[] playing = playingAccessors(state.getClass());
        if (paused.length == 0 && playing.length == 0) return null;
        boolean sawPlaying = false;
        for (Method accessor : paused) {
            try {
                Object result = accessor.invoke(state);
                if (result instanceof Boolean && (Boolean) result) return Boolean.FALSE;
            } catch (Throwable ignored) {
            }
        }
        for (Method accessor : playing) {
            try {
                Object result = accessor.invoke(state);
                if (result instanceof Boolean) {
                    sawPlaying = true;
                    if (!(Boolean) result) return Boolean.FALSE;
                }
            } catch (Throwable ignored) {
            }
        }
        // Only paused accessors: not paused is playing only if the session agrees it is active.
        return sawPlaying || sessionPlaying ? Boolean.TRUE : null;
    }

    private void forcePosition(long positionMs) {
        mediaPositionMs = Math.max(0, positionMs);
        mediaPositionUpdatedAtElapsedMs = SystemClock.elapsedRealtime();
        seekOverrideUntilElapsedMs = SystemClock.elapsedRealtime() + 1800;
    }

    /**
     * Spotify's own PlayerState#position(now): positionAsOfTimestamp advanced by the wall-clock
     * time since timestamp (System.currentTimeMillis, the clock Spotify itself passes) times
     * playbackSpeed. A PlayerState is immutable, so the three values are read and parsed once
     * per state object; the 33 ms resync only does the arithmetic. Speed is honoured as Spotify
     * reports it (podcasts at other speeds), and a buffering state does not advance - the
     * lyrics used to run on through a stall and jump back when playback resumed.
     */
    private Object parsedState;
    private long parsedBasePos = -1;
    private long parsedTimestamp;
    private double parsedSpeed = 1d;
    private boolean parsedBuffering;
    private Class<?> stateAccessorOwner;
    private Method positionAccessor;
    private Method timestampAccessor;
    private Method speedAccessor;
    private Method bufferingAccessor;

    private long readPlayerStateProgressMs(boolean playing) {
        try {
            Object state = References.playerState == null ? null : References.playerState.get();
            if (state == null) return -1;
            if (state != parsedState) parseState(state);
            if (parsedBasePos < 0) return -1;
            if (!playing || parsedBuffering || parsedTimestamp <= 0) return parsedBasePos;
            long advanced = Math.round((System.currentTimeMillis() - parsedTimestamp) * parsedSpeed);
            return Math.max(0, parsedBasePos + Math.max(0, advanced));
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private void parseState(Object state) throws ReflectiveOperationException {
        parsedState = state;
        Class<?> cls = state.getClass();
        if (cls != stateAccessorOwner) {
            stateAccessorOwner = cls;
            positionAccessor = accessor(cls, "positionAsOfTimestamp");
            timestampAccessor = accessor(cls, "timestamp");
            speedAccessor = accessor(cls, "playbackSpeed");
            bufferingAccessor = accessor(cls, "isBuffering");
        }
        parsedBasePos = positionAccessor == null ? -1
                : (long) leadingNumber(positionAccessor.invoke(state), -1d);
        Object ts = timestampAccessor == null ? null : timestampAccessor.invoke(state);
        parsedTimestamp = ts instanceof Long ? (Long) ts : 0L;
        // Absent speed (older builds) reads as normal speed, not as a stop.
        double speed = speedAccessor == null ? 1d : leadingNumber(speedAccessor.invoke(state), 1d);
        parsedSpeed = speed > 0d && speed < 8d ? speed : 1d;
        Object buffering = bufferingAccessor == null ? null : bufferingAccessor.invoke(state);
        parsedBuffering = buffering instanceof Boolean && (Boolean) buffering;
    }

    private static Method accessor(Class<?> cls, String name) {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod(name);
                if (Modifier.isStatic(m.getModifiers())) continue;
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {
            }
        }
        return null;
    }

    /** The first number in an Optional's toString ("Optional.of(1234)"), without a regex. */
    static double leadingNumber(Object value, double fallback) {
        if (value == null) return fallback;
        if (value instanceof Number) return ((Number) value).doubleValue();
        String s = value.toString();
        int i = 0;
        int n = s.length();
        while (i < n && !Character.isDigit(s.charAt(i))) i++;
        if (i == n) return fallback;
        int start = i;
        while (i < n && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) i++;
        try {
            return Double.parseDouble(s.substring(start, i));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
