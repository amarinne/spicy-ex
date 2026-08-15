package com.eza.spicyex.hooks;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.eza.spicyex.SpotifyPlusConfig;
import com.eza.spicyex.SpotifyTrack;
import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.lyrics.LyricsDocumentProcessor;
import com.eza.spicyex.lyrics.LyricsRenderConfig;
import com.eza.spicyex.lyrics.LyricsSecondaryProcessingSession;
import com.eza.spicyex.lyrics.LyricsSecondaryProcessor;
import com.eza.spicyex.lyrics.RomanizationOptions;
import com.eza.spicyex.lyrics.session.CanonicalBase;
import com.eza.spicyex.lyrics.session.CanonicalBaseAdoption;
import com.eza.spicyex.lyrics.session.CanonicalSourceCache;
import com.eza.spicyex.lyrics.session.AIPaidArtifactCache;
import com.eza.spicyex.beautifullyrics.entities.LyricsResponseCache;
import com.eza.spicyex.lyrics.CacheClearKind;
import com.eza.spicyex.lyrics.LyricCaches;
import com.eza.spicyex.lyrics.session.CanonicalSourceCodec;
import com.eza.spicyex.lyrics.session.LyricPipelineMetrics;
import com.eza.spicyex.lyrics.session.DerivedLayerArtifact;
import com.eza.spicyex.lyrics.session.LayerAuthority;
import com.eza.spicyex.lyrics.session.LayerKind;
import com.eza.spicyex.lyrics.session.LayerState;
import com.eza.spicyex.lyrics.session.LayerStatus;
import com.eza.spicyex.lyrics.session.LegacyDocumentComposer;
import com.eza.spicyex.lyrics.session.LyricSession;
import com.eza.spicyex.lyrics.session.LyricsSourcePolicy;

import de.robv.android.xposed.XposedBridge;

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

    interface SessionSubscription extends AutoCloseable {
        @Override void close();
    }

    interface PollingDemandLease extends AutoCloseable {
        @Override void close();
    }

    interface LyricsRequest extends AutoCloseable {
        @Override void close();
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
    private final List<SubscriptionRecord> subscriptions = new ArrayList<>();
    private final List<RequestRecord> requests = new ArrayList<>();
    private final LyricsSessionPolicy policy = new LyricsSessionPolicy();
    private final LyricsSecondaryProcessingSession secondaryProcessing;

    private SpotifyTrack track;
    private String loadingUri = "";
    private LyricsDocument document;
    private String status = "idle";
    private long nextFetchAtMs;
    private long missingTrackSinceMs;
    private boolean started;
    /**
     * The session itself: canonical base, source identity, and per-layer state.
     *
     * <p>Authoritative for identity. {@link #document} remains the legacy projection consumers
     * still receive; the two are kept in step here, and collapsing them is the last Phase 5 step.
     */
    private LyricSession session;
    /**
     * The canonical document as adopted, before any lane wrote on it.
     *
     * <p>The composer needs something to project artifacts over, and {@link #document} is mutated
     * in place by the lanes. Keeping the pristine copy is also where this ends up: once publication
     * reads from the session, this is the base and the mutable document goes away.
     */
    private LyricsDocument canonicalSource;
    /** Set when the source policy asked for a probe on top of an already-rendered cached base. */
    private boolean refreshRequested;
    private boolean sourceProbed;
    private String canonicalLoadingUri = "";

    LyricsSessionManager(NativeSpicyLyricsHook hook, LyricsFetchCoordinator fetchCoordinator, Context context) {
        this.hook = hook;
        this.fetchCoordinator = fetchCoordinator;
        Context app = context.getApplicationContext();
        this.context = app != null ? app : context;
        SpotifyPlusConfig config = SpotifyPlusConfig.from(this.context);
        LyricsSecondaryProcessor processor = new LyricsSecondaryProcessor(
                this.context, NativeRuntime.HTTP, NativeRuntime.SOUND_PROCESSOR,
                NativeRuntime.SOUND_WORKERS, NativeRuntime.MEANING_WORKERS, handler,
                NativeRuntime.GOOGLE_PROCESSING_VERSION);
        secondaryProcessing = new LyricsSecondaryProcessingSession(
                this.context, config, processor, NativeRuntime.GOOGLE_PROCESSING_VERSION,
                "[SpotifyPlusSession]");
    }

    void start() {
        if (started) return;
        started = true;
    }

    SessionSubscription subscribe(Listener listener) {
        if (listener == null) return () -> {};
        SubscriptionRecord record = new SubscriptionRecord(listener);
        subscriptions.add(record);
        if (!policy.trackUri().isEmpty()) {
            Snapshot snapshot = snapshot();
            listener.onSessionChanged(snapshot);
            if (document != null) listener.onDocumentChanged(snapshot, LyricsDocument.copyOf(document));
        }
        return record;
    }

    PollingDemandLease acquirePollingDemand() {
        if (policy.acquirePollingDemand()) handler.post(poll);
        return new DemandRecord();
    }

    LyricsRequest requestLyrics(SpotifyTrack requestedTrack,
                                NativeSpicyLyricsHook.LyricsResultCallback callback) {
        if (callback == null) return () -> {};
        if (requestedTrack == null || requestedTrack.uri == null || requestedTrack.uri.isEmpty()) {
            callback.onError("Missing Spotify track");
            return () -> {};
        }
        adoptTrack(requestedTrack);
        if (document != null) {
            callback.onSuccess(LyricsDocument.copyOf(publishedProjection(document)));
            return () -> {};
        }
        RequestRecord request = new RequestRecord(policy.generation(), callback);
        requests.add(request);
        maybeFetch();
        return request;
    }

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!policy.hasPollingDemand()) return;
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
                if (policy.hasPollingDemand()) handler.postDelayed(this, POLL_MS);
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
        canonicalSource = null;
        status = uri.isEmpty() ? "idle" : "loading";
        nextFetchAtMs = 0L;
        missingTrackSinceMs = 0L;
        session = null;
        refreshRequested = false;
        sourceProbed = false;
        canonicalLoadingUri = "";
        cancelRequests();
        // Abort the previous track's derived work rather than just ignoring its callbacks.
        secondaryProcessing.cancelActive();
        notifyState(snapshot());
        loadCanonicalBase(uri, policy.generation());
    }

    /**
     * Cache-first entry point: a durable canonical base renders before any derived processing and
     * without a source request. Only a miss, or an explicit policy probe, reaches the network.
     */
    private void loadCanonicalBase(String requestedUri, int requestedGeneration) {
        if (requestedUri.isEmpty() || requestedUri.equals(canonicalLoadingUri)) return;
        canonicalLoadingUri = requestedUri;
        final SpotifyTrack requestedTrack = track;
        final long startedAtMs = SystemClock.elapsedRealtime();
        NativeRuntime.LYRICS_IO.execute(() -> {
            CanonicalSourceCodec.Record record = null;
            try {
                record = CanonicalSourceCache.load(context, requestedUri);
                if (record != null) {
                    // Reproduce exactly what a fresh parse produces: timeline repair, provider
                    // translations, compatible cached derived values, and pending flags.
                    LyricsDocumentProcessor.finalizeParsedDocument(context, record.document,
                            NativeRuntime.GOOGLE_PROCESSING_VERSION);
                }
            } catch (Throwable t) {
                // A bad cache record must never strand the session: fall through to the network.
                record = null;
            }
            final CanonicalSourceCodec.Record loaded = record;
            handler.post(() -> acceptCachedBase(requestedTrack, requestedUri, requestedGeneration,
                    loaded, startedAtMs));
        });
    }

    private void acceptCachedBase(SpotifyTrack requestedTrack, String requestedUri,
                                  int requestedGeneration, CanonicalSourceCodec.Record record,
                                  long startedAtMs) {
        if (!requestedUri.equals(canonicalLoadingUri)) return;
        canonicalLoadingUri = "";
        if (!policy.accepts(requestedGeneration, requestedUri)) {
            LyricPipelineMetrics.increment(LyricPipelineMetrics.Counter.STALE_RESULT_REJECTED);
            return;
        }
        if (record == null || document != null) {
            maybeFetch();
            return;
        }
        document = record.document;
        canonicalSource = LyricsDocument.copyOf(record.document);
        session = LyricSession.of(CanonicalBase.fromDocument(requestedUri, record.document),
                requestedGeneration, record.sourceRevision);
        status = "ready";
        LyricPipelineMetrics.increment(LyricPipelineMetrics.Counter.CACHED_ORIGINAL_RENDER);
        LyricPipelineMetrics.record(LyricPipelineMetrics.Timing.CACHED_ORIGINAL_RENDER,
                SystemClock.elapsedRealtime() - startedAtMs);
        Snapshot snapshot = snapshot();
        for (RequestRecord request : takeRequests(requestedGeneration)) {
            request.callback.onSuccess(LyricsDocument.copyOf(record.document));
        }
        notifyDocument(snapshot, record.document);
        startSharedProcessing(requestedTrack, record.document, requestedGeneration);

        LyricsSourcePolicy.Decision decision = LyricsSourcePolicy.decide(true,
                LyricsSourcePolicy.isSynced(record.document.type), sourceProbed, false);
        if (decision == LyricsSourcePolicy.Decision.REFRESH_AFTER_CACHED_BASE) {
            refreshRequested = true;
            maybeFetch();
        }
    }

    private void clearCurrent() {
        adoptTrack(null);
    }

    private void maybeFetch() {
        if (track == null || policy.trackUri().isEmpty()
                || policy.trackUri().equals(loadingUri)
                // The durable canonical base may still resolve; never race it to the network.
                || !canonicalLoadingUri.isEmpty()
                // A rendered base is display authority. Only an explicit policy probe refreshes it.
                || (document != null && !refreshRequested)
                || SystemClock.elapsedRealtime() < nextFetchAtMs) return;
        final SpotifyTrack requestedTrack = track;
        final String requestedUri = policy.trackUri();
        final int requestedGeneration = policy.generation();
        loadingUri = requestedUri;
        refreshRequested = false;
        sourceProbed = true;
        LyricPipelineMetrics.increment(LyricPipelineMetrics.Counter.SOURCE_FETCH_CALL);
        if (document == null) {
            status = "loading";
            notifyState(snapshot());
        }
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
        if (result == null || result.lines.isEmpty()) return;
        if (!policy.accepts(requestedGeneration, requestedUri)) {
            LyricPipelineMetrics.increment(LyricPipelineMetrics.Counter.STALE_RESULT_REJECTED);
            return;
        }
        loadingUri = "";
        CanonicalBase incoming = CanonicalBase.fromDocument(requestedUri, result);
        CanonicalBaseAdoption.Outcome outcome = CanonicalBaseAdoption.evaluate(
                session != null, session == null ? "" : session.identity.canonicalDigest,
                incoming.digest);
        if (outcome == CanonicalBaseAdoption.Outcome.UNCHANGED) {
            // Same canonical source arrived again. Nothing changed, so nothing republishes and no
            // derived artifact is invalidated.
            persistCanonicalBase(requestedUri, result,
                    session == null ? 1 : session.identity.sourceRevision, incoming.digest);
            return;
        }
        // withReplacedBase increments the source revision and drops artifacts tied to the old
        // digest, which is the whole invalidation axis for a source change.
        session = session == null
                ? LyricSession.of(incoming, requestedGeneration)
                : session.withReplacedBase(incoming);
        document = result;
        canonicalSource = LyricsDocument.copyOf(result);
        status = "ready";
        LyricPipelineMetrics.increment(LyricPipelineMetrics.Counter.FRESH_SOURCE_ACQUIRED);
        if (outcome == CanonicalBaseAdoption.Outcome.REPLACE) {
            LyricPipelineMetrics.increment(LyricPipelineMetrics.Counter.SOURCE_REPLACED);
        }
        persistCanonicalBase(requestedUri, result, session.identity.sourceRevision, incoming.digest);
        Snapshot snapshot = snapshot();
        List<RequestRecord> pending = takeRequests(requestedGeneration);
        for (RequestRecord request : pending) {
            request.callback.onSuccess(LyricsDocument.copyOf(result));
        }
        notifyDocument(snapshot, result);
        startSharedProcessing(requestedTrack, result, requestedGeneration);
    }

    private void persistCanonicalBase(String requestedUri, LyricsDocument result, int revision,
                                      String digest) {
        final LyricsDocument snapshot = LyricsDocument.copyOf(result);
        NativeRuntime.LYRICS_IO.execute(
                () -> CanonicalSourceCache.save(context, requestedUri, snapshot, revision, digest));
    }

    private void acceptError(String requestedUri, int requestedGeneration, String error) {
        if (!policy.accepts(requestedGeneration, requestedUri)) return;
        loadingUri = "";
        nextFetchAtMs = SystemClock.elapsedRealtime() + RETRY_MS;
        // A failed optional refresh must not replace the cached base with an error state.
        if (document != null) return;
        status = "no_lyrics";
        List<RequestRecord> pending = takeRequests(requestedGeneration);
        for (RequestRecord request : pending) {
            request.callback.onError(error);
        }
        notifyState(snapshot());
    }

    /**
     * Re-runs one derived layer for the current track after its settings changed.
     *
     * <p>Render surfaces call this instead of owning a provider run: the session is the only
     * scheduler, so a settings change costs one run no matter how many surfaces are open.
     */
    void refreshLayer(com.eza.spicyex.lyrics.session.LayerKind layer) {
        if (track == null || document == null || policy.trackUri().isEmpty()) return;
        if (layer == com.eza.spicyex.lyrics.session.LayerKind.MEANING) {
            LyricsDocumentProcessor.resetMeaningLayer(context, document);
        } else {
            LyricsDocumentProcessor.resetSoundLayer(context, document);
        }
        // Drop what the layer was showing as well. Publication composes from the session, so a
        // retained artifact would keep the old output on screen through the refresh.
        if (session != null) session = session.withLayer(layer, LayerState.absent(layer));
        // Do not publish the cleared intermediate state: surfaces would flash empty readings or
        // translations before the lane refills them. Publication happens on layer completion, and
        // a layer with no work still completes, so turning a layer off still reaches every surface.
        startSharedProcessing(track, document, policy.generation());
    }

    /**
     * Clears a user-selected cache as one live-session transaction.
     *
     * <p>Retiring the lanes before deleting storage prevents an already-running completion from
     * immediately writing the stale artifact back. Derived clears then reset and re-run that layer
     * so every mounted surface receives the newly computed result. A lyrics-response clear also
     * drops the durable canonical source and reloads the current track from providers.
     */
    void clearCache(CacheClearKind kind) {
        if (kind == null) return;
        secondaryProcessing.cancelActive();
        switch (kind) {
            case TRANSLATION:
                LyricCaches.clearGoogle(context);
                LyricCaches.clearMeaningArtifacts(context);
                AIPaidArtifactCache.clearLayer(context, LayerKind.MEANING);
                refreshLayer(LayerKind.MEANING);
                break;
            case TRANSLITERATION:
                LyricCaches.clearSoundArtifacts(context);
                AIPaidArtifactCache.clearLayer(context, LayerKind.SOUND);
                refreshLayer(LayerKind.SOUND);
                break;
            case LYRICS_RESPONSE:
                LyricsResponseCache.clear(context);
                CanonicalSourceCache.clear(context);
                reloadCurrentSource();
                break;
        }
    }

    private void reloadCurrentSource() {
        if (track == null || policy.trackUri().isEmpty()) return;
        fetchCoordinator.invalidate(track);
        loadingUri = "";
        canonicalLoadingUri = "";
        document = null;
        canonicalSource = null;
        session = null;
        sourceProbed = false;
        refreshRequested = true;
        status = "loading";
        notifyState(snapshot());
        maybeFetch();
    }

    /** Read at use time, not construction: settings can change while a session is alive. */
    private LyricsRenderConfig renderConfig() {
        return LyricsRenderConfig.read(context, SpotifyPlusConfig.from(context));
    }

    private static RomanizationOptions romanizationOptions(LyricsRenderConfig config) {
        return new RomanizationOptions(config.defaultChineseMode, config.koreanMode,
                config.chineseTones, config.defaultCyrillicMode, config.cyrillicKeepSigns);
    }

    private void startSharedProcessing(SpotifyTrack requestedTrack, LyricsDocument snapshot, int requestedGeneration) {
        LyricsRenderConfig config = renderConfig();
        java.util.Set<LayerKind> started = secondaryProcessing.start(snapshot.trackId, requestedGeneration, snapshot,
                config.transliterationEnabled, romanizationOptions(config),
                (id, callbackGeneration, callbackSnapshot) -> callbackGeneration == policy.generation()
                        && callbackSnapshot == document
                        && requestedTrack.uri.equals(policy.trackUri()),
                new LyricsSecondaryProcessingSession.Callback() {
                    @Override public void status(String message) {}
                    @Override public void rerender(LayerKind layer, DerivedLayerArtifact partial,
                                                   LyricsDocument processed, String message) {
                        foldLayerDelta(layer, partial, processed, requestedGeneration);
                        publishProcessed(processed, requestedGeneration);
                    }
                    @Override public void progress(LyricsDocument processed, String message) {}
                    @Override public void complete(LayerKind layer, DerivedLayerArtifact artifact,
                                                   LyricsDocument processed, String message, int changed) {
                        adoptLayerArtifact(layer, artifact, processed, requestedGeneration);
                        publishProcessed(processed, requestedGeneration);
                    }
                });
        markLanesRunning(started, snapshot, config);
    }

    /**
     * Marks the layers whose lanes actually began work as running.
     *
     * <p>A completion callback cannot express this: a layer with nothing to do also completes. The
     * distinction is what the surfaces' progress indicators read, and having it on the session is
     * what lets publication stop reading flags off the mutable document.
     */
    private void markLanesRunning(java.util.Set<LayerKind> started, LyricsDocument snapshot,
                                  LyricsRenderConfig config) {
        if (session == null || started.isEmpty()) return;
        for (LayerKind layer : started) {
            LayerState state = session.layer(layer);
            session = session.withLayer(layer, state.processing(
                    layer == LayerKind.SOUND ? LayerAuthority.DETERMINISTIC : LayerAuthority.MACHINE,
                    layer == LayerKind.SOUND
                            ? LyricsDocumentProcessor.currentSoundConfigId(context, snapshot)
                            : LyricsDocumentProcessor.meaningConfigId(context),
                    "", ""));
        }
    }

    /**
     * Lands a lane's artifact on the session's layer state.
     *
     * <p>The document still carries the same values to consumers, so this changes nothing visible.
     * What it changes is where the truth lives: the session now holds each layer's artifact,
     * provenance, and status, which is what publication will read from once the callback is
     * replaced by the event stream. Composing the session back over the canonical document must
     * reproduce what the lanes wrote, so the two are compared and any divergence is counted.
     */
    private void adoptLayerArtifact(LayerKind layer, DerivedLayerArtifact artifact,
                                    LyricsDocument processed, int requestedGeneration) {
        if (session == null || processed != document || requestedGeneration != policy.generation()) return;
        LayerState state = session.layer(layer);
        session = session.withLayer(layer, artifact == null
                ? LayerState.absent(layer)
                : state.withArtifact(LayerStatus.READY, artifact, ""));
        syncDocumentLayerFlags();
    }

    /** Folds a lane's partial output in while it keeps working, leaving the layer running. */
    private void foldLayerDelta(LayerKind layer, DerivedLayerArtifact partial,
                                LyricsDocument processed, int requestedGeneration) {
        if (session == null || partial == null || processed != document
                || requestedGeneration != policy.generation()) {
            return;
        }
        session = session.withLayer(layer, session.layer(layer).withDelta(partial, LayerStatus.PROCESSING));
        syncDocumentLayerFlags();
    }

    /**
     * Keeps the legacy document's layer flags true to the session.
     *
     * <p>The lanes no longer write to the document, but they still read these flags to decide
     * whether they have work — so a Meaning-only refresh must not look like the Sound layer is
     * outstanding again. The session owns them now; the document is a projection target.
     */
    private void syncDocumentLayerFlags() {
        if (document == null || session == null) return;
        document.romanizationPending = session.sound.status == LayerStatus.PROCESSING;
        document.translationPending = session.meaning.status == LayerStatus.PROCESSING;
        document.processingPending = document.romanizationPending || document.translationPending;
        document.includesRomanization = session.sound.artifact != null && !session.sound.artifact.isEmpty();
        document.includesTranslation = session.meaning.artifact != null && !session.meaning.artifact.isEmpty();
    }

    private void publishProcessed(LyricsDocument processed, int requestedGeneration) {
        if (processed != document || requestedGeneration != policy.generation()) return;
        notifyDocument(snapshot(), processed);
    }

    private Snapshot snapshot() {
        boolean playing = track != null && hook.isPlayerActuallyPlaying();
        long position = track == null ? 0L : hook.readBestMeasuredProgressMs(track, playing);
        return new Snapshot(track, policy.trackUri(), policy.generation(), status, playing, position,
                SystemClock.elapsedRealtime());
    }

    private void notifyState(Snapshot snapshot) {
        for (SubscriptionRecord record : new ArrayList<>(subscriptions)) {
            if (record.lifetime.isActive()) record.listener.onSessionChanged(snapshot);
        }
    }

    private void notifyDocument(Snapshot snapshot, LyricsDocument value) {
        LyricsDocument published = publishedProjection(value);
        for (SubscriptionRecord record : new ArrayList<>(subscriptions)) {
            if (record.lifetime.isActive()) {
                record.listener.onDocumentChanged(snapshot, LyricsDocument.copyOf(published));
            }
        }
    }

    /**
     * What subscribers receive: derived text composed from the session's artifacts over the
     * canonical document, rather than read off the document the lanes wrote on.
     *
     * <p>The lanes no longer write derived text anywhere else, so this is the only place it comes
     * from. The fallback below returns the raw document if composition ever throws — that would
     * publish original lyrics without readings or translations, which is degraded but honest, and
     * {@code COMPOSED_PROJECTION_MISMATCH} records it.
     */
    private LyricsDocument publishedProjection(LyricsDocument value) {
        if (session == null || canonicalSource == null || value == null) return value;
        if (session.sound.artifact == null && session.meaning.artifact == null) return value;
        try {
            LyricsDocument composed = LegacyDocumentComposer.compose(canonicalSource, session);
            if (composed == null || composed.lines.size() != value.lines.size()) return value;
            return composed;
        } catch (Throwable t) {
            LyricPipelineMetrics.increment(LyricPipelineMetrics.Counter.COMPOSED_PROJECTION_MISMATCH);
            return value;
        }
    }

    private List<RequestRecord> takeRequests(int generation) {
        List<RequestRecord> pending = new ArrayList<>();
        for (RequestRecord request : new ArrayList<>(requests)) {
            if (request.lifetime.isActive() && request.generation == generation
                    && request.lifetime.consume()) {
                requests.remove(request);
                pending.add(request);
            }
        }
        return pending;
    }

    private void cancelRequests() {
        for (RequestRecord request : new ArrayList<>(requests)) request.close();
    }

    private final class SubscriptionRecord implements SessionSubscription {
        final Listener listener;
        final LyricsSessionLifecycle.HandleState lifetime =
                new LyricsSessionLifecycle.HandleState();

        SubscriptionRecord(Listener listener) {
            this.listener = listener;
        }

        @Override public void close() {
            if (!lifetime.close()) return;
            subscriptions.remove(this);
        }
    }

    private final class DemandRecord implements PollingDemandLease {
        final LyricsSessionLifecycle.HandleState lifetime =
                new LyricsSessionLifecycle.HandleState();

        @Override public void close() {
            if (!lifetime.close()) return;
            if (policy.releasePollingDemand()) handler.removeCallbacks(poll);
        }
    }

    private final class RequestRecord implements LyricsRequest {
        final int generation;
        final NativeSpicyLyricsHook.LyricsResultCallback callback;
        final LyricsSessionLifecycle.HandleState lifetime =
                new LyricsSessionLifecycle.HandleState();

        RequestRecord(int generation, NativeSpicyLyricsHook.LyricsResultCallback callback) {
            this.generation = generation;
            this.callback = callback;
        }

        @Override public void close() {
            if (!lifetime.close()) return;
            requests.remove(this);
        }
    }
}
