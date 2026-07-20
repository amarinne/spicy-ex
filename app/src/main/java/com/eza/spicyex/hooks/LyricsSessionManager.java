package com.eza.spicyex.hooks;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.eza.spicyex.SpotifyPlusConfig;
import com.eza.spicyex.SpotifyTrack;
import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.lyrics.LyricsRenderConfig;
import com.eza.spicyex.lyrics.LyricsSecondaryProcessingSession;
import com.eza.spicyex.lyrics.LyricsSecondaryProcessor;
import com.eza.spicyex.lyrics.RomanizationOptions;

import java.util.ArrayList;
import java.util.List;

/** Spotify-main-process owner for current-track lyric fetch and shared processing state. */
final class LyricsSessionManager {
    static final long POLL_MS = 200L;
    static final long RETRY_MS = 5000L;

    interface Listener {
        void onSessionChanged(Snapshot snapshot);
        void onDocumentChanged(Snapshot snapshot, LyricsDocument document);
    }

    static final class Snapshot {
        final SpotifyTrack track;
        final String trackUri;
        final int generation;
        final String status;
        final boolean playing;
        final long positionMs;
        final long sampledAtMs;

        Snapshot(SpotifyTrack track, String trackUri, int generation, String status,
                 boolean playing, long positionMs, long sampledAtMs) {
            this.track = track;
            this.trackUri = trackUri;
            this.generation = generation;
            this.status = status;
            this.playing = playing;
            this.positionMs = positionMs;
            this.sampledAtMs = sampledAtMs;
        }
    }

    private final NativeSpicyLyricsHook hook;
    private final LyricsFetchCoordinator fetchCoordinator;
    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    private final List<NativeSpicyLyricsHook.LyricsResultCallback> requestCallbacks = new ArrayList<>();
    private final LyricsSessionPolicy policy = new LyricsSessionPolicy();
    private final LyricsSecondaryProcessingSession secondaryProcessing;
    private final LyricsRenderConfig renderConfig;
    private final RomanizationOptions romanizationOptions;

    private SpotifyTrack track;
    private String loadingUri = "";
    private LyricsDocument document;
    private String status = "idle";
    private long nextFetchAtMs;
    private long missingTrackSinceMs;
    private boolean started;

    LyricsSessionManager(NativeSpicyLyricsHook hook, LyricsFetchCoordinator fetchCoordinator, Context context) {
        this.hook = hook;
        this.fetchCoordinator = fetchCoordinator;
        Context app = context.getApplicationContext();
        this.context = app != null ? app : context;
        SpotifyPlusConfig config = SpotifyPlusConfig.from(this.context);
        renderConfig = LyricsRenderConfig.read(this.context, config);
        romanizationOptions = new RomanizationOptions(
                renderConfig.defaultChineseMode, renderConfig.koreanMode, renderConfig.chineseTones,
                renderConfig.defaultCyrillicMode, renderConfig.cyrillicKeepSigns);
        LyricsSecondaryProcessor processor = new LyricsSecondaryProcessor(
                this.context, NativeRuntime.HTTP, NativeRuntime.PROCESSOR,
                NativeRuntime.GOOGLE_WORKERS, handler, NativeRuntime.GOOGLE_PROCESSING_VERSION);
        secondaryProcessing = new LyricsSecondaryProcessingSession(
                this.context, config, processor, NativeRuntime.GOOGLE_PROCESSING_VERSION,
                "[SpotifyPlusSession]");
    }

    void start() {
        if (started) return;
        started = true;
    }

    void addListener(Listener listener) {
        if (!listeners.contains(listener)) listeners.add(listener);
        if (!policy.trackUri().isEmpty()) {
            Snapshot snapshot = snapshot();
            listener.onSessionChanged(snapshot);
            if (document != null) listener.onDocumentChanged(snapshot, document);
        }
    }

    void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    void setBackgroundDemand(boolean enabled) {
        if (policy.hasBackgroundDemand() == enabled) return;
        policy.setBackgroundDemand(enabled);
        handler.removeCallbacks(poll);
        if (enabled) handler.post(poll);
    }

    void requestLyrics(SpotifyTrack requestedTrack, NativeSpicyLyricsHook.LyricsResultCallback callback) {
        if (requestedTrack == null || requestedTrack.uri == null || requestedTrack.uri.isEmpty()) {
            callback.onError("Missing Spotify track");
            return;
        }
        adoptTrack(requestedTrack);
        if (!requestCallbacks.contains(callback)) requestCallbacks.add(callback);
        if (document != null) callback.onSuccess(LyricsDocument.copyOf(document));
        maybeFetch();
    }

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!policy.hasBackgroundDemand()) return;
            try {
                SpotifyTrack current = hook.getCurrentTrackSafely();
                if (current == null || current.uri == null || current.uri.isEmpty()) {
                    if (!policy.trackUri().isEmpty()) {
                        long now = SystemClock.elapsedRealtime();
                        if (missingTrackSinceMs == 0L) missingTrackSinceMs = now;
                        if (now - missingTrackSinceMs >= 1000L) clearCurrent();
                    }
                } else {
                    missingTrackSinceMs = 0L;
                    adoptTrack(current);
                    boolean playing = hook.isPlayerActuallyPlaying();
                    long position = hook.readBestMeasuredProgressMs(current, playing);
                    notifyState(new Snapshot(current, policy.trackUri(), policy.generation(), status, playing,
                            position, SystemClock.elapsedRealtime()));
                    maybeFetch();
                }
            } catch (Throwable ignored) {
                // Spotify player internals are version-fragile. Keep session polling alive.
            } finally {
                if (policy.hasBackgroundDemand()) handler.postDelayed(this, POLL_MS);
            }
        }
    };

    private void adoptTrack(SpotifyTrack next) {
        String uri = next == null || next.uri == null ? "" : next.uri;
        if (!policy.adoptTrack(uri)) {
            track = next;
            return;
        }
        track = next;
        loadingUri = "";
        document = null;
        status = uri.isEmpty() ? "idle" : "loading";
        nextFetchAtMs = 0L;
        missingTrackSinceMs = 0L;
        requestCallbacks.clear();
        notifyState(snapshot());
    }

    private void clearCurrent() {
        adoptTrack(null);
    }

    private void maybeFetch() {
        if (track == null || policy.trackUri().isEmpty() || document != null
                || policy.trackUri().equals(loadingUri) || SystemClock.elapsedRealtime() < nextFetchAtMs) return;
        final SpotifyTrack requestedTrack = track;
        final String requestedUri = policy.trackUri();
        final int requestedGeneration = policy.generation();
        loadingUri = requestedUri;
        status = "loading";
        notifyState(snapshot());
        fetchCoordinator.fetchLyrics(context, requestedTrack, requestedGeneration,
                new NativeSpicyLyricsHook.LyricsResultCallback() {
                    @Override public void onSuccess(LyricsDocument result) {
                        handler.post(() -> acceptDocument(requestedTrack, requestedUri, requestedGeneration, result));
                    }

                    @Override public void onError(String error) {
                        handler.post(() -> acceptError(requestedUri, requestedGeneration, error));
                    }
                });
    }

    private void acceptDocument(SpotifyTrack requestedTrack, String requestedUri,
                                int requestedGeneration, LyricsDocument result) {
        if (result == null || !policy.accepts(requestedGeneration, requestedUri)) return;
        loadingUri = "";
        document = result;
        status = "ready";
        Snapshot snapshot = snapshot();
        for (NativeSpicyLyricsHook.LyricsResultCallback callback : new ArrayList<>(requestCallbacks)) {
            callback.onSuccess(LyricsDocument.copyOf(result));
        }
        notifyDocument(snapshot, result);
        startSharedProcessing(requestedTrack, result, requestedGeneration);
    }

    private void acceptError(String requestedUri, int requestedGeneration, String error) {
        if (!policy.accepts(requestedGeneration, requestedUri)) return;
        loadingUri = "";
        status = "no_lyrics";
        nextFetchAtMs = SystemClock.elapsedRealtime() + RETRY_MS;
        for (NativeSpicyLyricsHook.LyricsResultCallback callback : new ArrayList<>(requestCallbacks)) {
            callback.onError(error);
        }
        requestCallbacks.clear();
        notifyState(snapshot());
    }

    private void startSharedProcessing(SpotifyTrack requestedTrack, LyricsDocument snapshot, int requestedGeneration) {
        secondaryProcessing.start(snapshot.trackId, requestedGeneration, snapshot,
                renderConfig.transliterationEnabled, romanizationOptions,
                (id, callbackGeneration, callbackSnapshot) -> callbackGeneration == policy.generation()
                        && callbackSnapshot == document
                        && requestedTrack.uri.equals(policy.trackUri()),
                new LyricsSecondaryProcessingSession.Callback() {
                    @Override public void status(String message) {}
                    @Override public void rerender(LyricsDocument processed, String message) {
                        publishProcessed(processed, requestedGeneration);
                    }
                    @Override public void progress(LyricsDocument processed, String message) {}
                    @Override public void complete(LyricsDocument processed, String message, int changed) {
                        publishProcessed(processed, requestedGeneration);
                    }
                });
    }

    private void publishProcessed(LyricsDocument processed, int requestedGeneration) {
        if (processed != document || requestedGeneration != policy.generation()) return;
        for (NativeSpicyLyricsHook.LyricsResultCallback callback : new ArrayList<>(requestCallbacks)) {
            callback.onSuccess(LyricsDocument.copyOf(processed));
        }
        notifyDocument(snapshot(), processed);
    }

    private Snapshot snapshot() {
        boolean playing = track != null && hook.isPlayerActuallyPlaying();
        long position = track == null ? 0L : hook.readBestMeasuredProgressMs(track, playing);
        return new Snapshot(track, policy.trackUri(), policy.generation(), status, playing, position,
                SystemClock.elapsedRealtime());
    }

    private void notifyState(Snapshot snapshot) {
        for (Listener listener : new ArrayList<>(listeners)) listener.onSessionChanged(snapshot);
    }

    private void notifyDocument(Snapshot snapshot, LyricsDocument value) {
        for (Listener listener : new ArrayList<>(listeners)) listener.onDocumentChanged(snapshot, value);
    }
}
