package com.eza.spicyex.hooks;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.eza.spicyex.SpotifyPlusConfig;
import com.eza.spicyex.SpotifyTrack;
import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.lyrics.LyricsDocumentProcessor;
import com.eza.spicyex.lyrics.LyricsFetchDiagnosticsState;
import com.eza.spicyex.lyrics.LyricsRenderConfig;
import com.eza.spicyex.lyrics.LyricsSecondaryProcessingSession;
import com.eza.spicyex.lyrics.LyricsSecondaryProcessor;
import com.eza.spicyex.lyrics.RomanizationOptions;
import com.eza.spicyex.lyrics.session.CanonicalBase;
import com.eza.spicyex.lyrics.session.CanonicalBaseAdoption;
import com.eza.spicyex.lyrics.session.CanonicalSourceCache;
import com.eza.spicyex.lyrics.session.AIPaidArtifactCache;
import com.eza.spicyex.lyrics.session.DetectionArtifact;
import com.eza.spicyex.lyrics.session.LyricsDetectionSession;
import com.eza.spicyex.lyrics.session.LyricsMemoryPressure;
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
import com.eza.spicyex.lyrics.session.LyricsSourcePreferences;
import com.eza.spicyex.lyrics.session.MeaningArtifact;
import com.eza.spicyex.lyrics.ai.AiRequestStartResult;
import com.eza.spicyex.lyrics.ai.AiSettings;


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
    /** Session-owned detection: one run per canonical digest, shared by both render surfaces. */
    private final LyricsDetectionSession detectionSession;

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
    /** Last completed detection for the current base, for diagnostics and status. */
    private DetectionArtifact detectionArtifact;
    /**
     * True while the initial lane start is waiting on detection.
     *
     * <p>A settings refresh that arrives before detection lands starts the lanes itself; the
     * detection completion must then only attach its rows, not start a second run.
     */
    private boolean awaitingDetection;

    LyricsSessionManager(NativeSpicyLyricsHook hook, LyricsFetchCoordinator fetchCoordinator, Context context) {
        this.hook = hook;
        this.fetchCoordinator = fetchCoordinator;
        Context app = context.getApplicationContext();
        this.context = app != null ? app : context;
        SpotifyPlusConfig config = SpotifyPlusConfig.from(this.context);
        LyricsSecondaryProcessor processor = new LyricsSecondaryProcessor(
                this.context, NativeRuntime.HTTP, NativeRuntime.SOUND_PROCESSOR,
                NativeRuntime.SOUND_WORKERS, NativeRuntime.MEANING_WORKERS, NativeRuntime.AI_WORKERS, handler,
                NativeRuntime.GOOGLE_PROCESSING_VERSION);
        secondaryProcessing = new LyricsSecondaryProcessingSession(
                this.context, config, processor, NativeRuntime.GOOGLE_PROCESSING_VERSION,
                "[SpotifyPlusSession]");
        detectionSession = new LyricsDetectionSession(this.context, NativeRuntime.LYRICS_IO);
        LyricsMemoryPressure.addReclaimer(level -> detectionSession.trimMemory());
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
            // A resumed surface is a new subscriber. Replay the same composed projection used by
            // normal publications; the mutable legacy document no longer carries lane artifacts.
            // Sending it raw drops AI/Google Meaning while deterministic/local Sound can survive,
            // which presents as translation disappearing after fullscreen exit.
            if (document != null) {
                listener.onDocumentChanged(snapshot, publishedProjection(document));
            }
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
            callback.onSuccess(publishedProjection(document));
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
        detectionArtifact = null;
        awaitingDetection = false;
        cancelRequests();
        // Abort the previous track's derived work rather than just ignoring its callbacks.
        secondaryProcessing.cancelActive();
        detectionSession.cancelActive();
        notifyState(snapshot());
        loadCanonicalBase(uri, policy.generation());
    }

    /** True when a legacy cached record survives an explicit per-track source override. */
    private static boolean overrideAcceptsRecord(LyricsSourcePreferences.Source override,
                                                 LyricsDocument record) {
        if (override == null || record == null) return true;
        String hay = (nz(record.fetchSource) + " " + nz(record.provider))
                .toLowerCase(java.util.Locale.ROOT);
        switch (override) {
            case LRCLIB:
                return hay.contains("lrclib");
            case SPOTIFY:
                return hay.contains("native") || hay.contains("musixmatch") || hay.contains("spotify");
            case SPICY:
            case APPLE_MUSIC:
                return hay.contains("spicy") || hay.contains("apple") || hay.contains("aml")
                        || hay.contains("lenerd");
            default:
                return true;
        }
    }

    private static String nz(String value) {
        return value == null ? "" : value;
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
                String selectionIdentity = LyricsSourcePreferences.selectionIdentity(context, requestedUri);
                // A karaoke version's stored lyrics are the original song's, fetched while
                // "Show original lyrics for karaoke versions" was on: with it off they are not
                // this track's, so the stored record is not served (and not deleted, for when
                // the option comes back on).
                boolean karaokeOriginalsOff = requestedTrack != null
                        && com.eza.spicyex.lyrics.KaraokeTitles.isKaraokeVersion(requestedTrack.title)
                        && !com.eza.spicyex.SpotifyPlusConfig.from(context)
                                .get(com.eza.spicyex.Settings.KARAOKE_ORIGINAL_LYRICS);
                record = karaokeOriginalsOff ? null
                        : CanonicalSourceCache.load(context, requestedUri, selectionIdentity);
                if (record == null && !karaokeOriginalsOff) {
                    // Migration: a record orphaned by a retired source (or any identity change)
                    // stays display-authoritative. Serve it unless an explicit per-track override
                    // rejects its source; the refresh policy still probes when the base leaves
                    // room for better, and the adoption gate refuses any lower-quality replace.
                    CanonicalSourceCodec.Record legacy =
                            CanonicalSourceCache.load(context, requestedUri);
                    if (legacy != null && legacy.document != null && overrideAcceptsRecord(
                            LyricsSourcePreferences.trackOverride(context,
                                    com.eza.spicyex.lyrics.LyricUtils.trackIdFromUri(requestedUri)),
                            legacy.document)) {
                        record = legacy;
                    }
                }
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
        LyricsFetchDiagnosticsState.recordCached(record.document);
        LyricPipelineMetrics.increment(LyricPipelineMetrics.Counter.CACHED_ORIGINAL_RENDER);
        LyricPipelineMetrics.record(LyricPipelineMetrics.Timing.CACHED_ORIGINAL_RENDER,
                SystemClock.elapsedRealtime() - startedAtMs);
        Snapshot snapshot = snapshot();
        for (RequestRecord request : takeRequests(requestedGeneration)) {
            request.callback.onSuccess(LyricsDocument.copyOf(record.document));
        }
        notifyDocument(snapshot, record.document);
        startDetection(requestedTrack, record.document, requestedGeneration);

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
        try {
            fetchCoordinator.fetchLyrics(context, requestedTrack, requestedGeneration,
                    new NativeSpicyLyricsHook.LyricsResultCallback() {
                        @Override public void onSuccess(LyricsDocument result) {
                            handler.post(() -> acceptDocument(requestedTrack, requestedUri, requestedGeneration, result));
                        }

                        @Override public void onError(String error) {
                            handler.post(() -> acceptError(requestedUri, requestedGeneration, error));
                        }
                    });
        } catch (Throwable launchFailed) {
            // A fetch that never starts must not keep the fetch gate armed: that strands the
            // session on stale rows with every later maybeFetch declining to run.
            NativeSpicyLyricsHook.dbg("maybeFetch",
                    "fetch launch failed: " + launchFailed.getClass().getSimpleName());
            loadingUri = "";
            nextFetchAtMs = SystemClock.elapsedRealtime() + RETRY_MS;
        }
    }

    private void acceptDocument(SpotifyTrack requestedTrack, String requestedUri,
                                 int requestedGeneration, LyricsDocument result) {
        if (result == null || result.lines.isEmpty()) {
            // An empty success is a failed fetch, not a document: release the fetch gate so a
            // later poll can retry instead of stranding the session on stale rows forever.
            NativeSpicyLyricsHook.dbg("acceptDocument", "empty result; releasing fetch gate");
            loadingUri = "";
            nextFetchAtMs = SystemClock.elapsedRealtime() + RETRY_MS;
            if (document != null) return;
            status = "no_lyrics";
            notifyState(snapshot());
            return;
        }
        if (!policy.accepts(requestedGeneration, requestedUri)) {
            LyricPipelineMetrics.increment(LyricPipelineMetrics.Counter.STALE_RESULT_REJECTED);
            return;
        }
        if (!CanonicalBaseAdoption.shouldSupersede(document, result)) {
            // A lower-quality refetch never overwrites the better base, on screen or in the
            // cache. Release the fetch gate so later polls are not stranded behind it.
            NativeSpicyLyricsHook.dbg("acceptDocument", "keeping higher-quality base");
            loadingUri = "";
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
            persistCanonicalBase(requestedTrack, requestedUri, result,
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
        persistCanonicalBase(requestedTrack, requestedUri, canonicalSource,
                session.identity.sourceRevision, incoming.digest);
        Snapshot snapshot = snapshot();
        List<RequestRecord> pending = takeRequests(requestedGeneration);
        for (RequestRecord request : pending) {
            request.callback.onSuccess(LyricsDocument.copyOf(result));
        }
        notifyDocument(snapshot, result);
        startDetection(requestedTrack, result, requestedGeneration);
    }

    private void persistCanonicalBase(SpotifyTrack requestedTrack, String requestedUri,
                                      LyricsDocument snapshot, int revision, String digest) {
        final String title = requestedTrack == null || requestedTrack.title == null ? "" : requestedTrack.title;
        final String artist = requestedTrack == null || requestedTrack.artist == null ? "" : requestedTrack.artist;
        // The caller owns this snapshot and must not mutate it after handoff: the IO thread reads
        // it without a further whole-document copy.
        final LyricsDocument toPersist = snapshot;
        NativeRuntime.LYRICS_IO.execute(
                () -> CanonicalSourceCache.save(context, requestedUri, toPersist, revision, digest,
                        LyricsSourcePreferences.selectionIdentity(context, requestedUri),
                        title, artist));
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
        // A user-initiated refresh must not wait on detection; the completion only attaches rows.
        awaitingDetection = false;
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
     * Explicit model action. Keeps the displayed artifact while the paid/reuse run settles.
     *
     * @return one stable reason for acceptance or refusal
     */
    AiRequestStartResult requestAiLayer(LayerKind layer) {
        if (layer == null || track == null || document == null || policy.trackUri().isEmpty()) {
            return AiRequestStartResult.INVALID_CONTEXT;
        }
        if (!new AiSettings(context).isConfigured()) {
            return AiRequestStartResult.NOT_CONFIGURED;
        }
        if (session != null) {
            LayerState layerState = session.layer(layer);
            if (layerState.status == LayerStatus.PROCESSING
                    && layerState.authority == LayerAuthority.AI) {
                return AiRequestStartResult.ALREADY_IN_FLIGHT;
            }
        }
        awaitingDetection = false;
        if (layer == LayerKind.MEANING) {
            LyricsDocumentProcessor.resetMeaningLayer(context, document);
        } else {
            LyricsDocumentProcessor.resetSoundLayer(context, document);
        }
        return startSharedProcessing(track, document, policy.generation(),
                java.util.EnumSet.of(layer)).contains(layer)
                ? AiRequestStartResult.STARTED : AiRequestStartResult.NOTHING_TO_DO;
    }

    /** Drops the accepted AI overlay and republishes the canonical baseline only. */
    void restoreLayer(LayerKind layer) {
        if (layer == null || document == null || session == null) return;
        secondaryProcessing.cancelActive();
        LayerState restored = LayerState.absent(layer);
        if (layer == LayerKind.MEANING
                && session.meaning.artifact instanceof MeaningArtifact) {
            MeaningArtifact baseline = ((MeaningArtifact) session.meaning.artifact).googleBaseline();
            if (baseline != null) {
                restored = restored.withArtifact(LayerStatus.READY, baseline, "");
            }
        }
        session = session.withLayer(layer, restored);
        document = canonicalSource == null ? LyricsDocument.copyOf(document)
                : LegacyDocumentComposer.compose(canonicalSource, session);
        syncDocumentLayerFlags();
        notifyDocument(snapshot(), document);
    }

    /**
     * Clears a user-selected cache as one live-session transaction.
     *
     * <p>Retiring the lanes before deleting storage prevents an already-running completion from
     * immediately writing the stale artifact back. Derived clears then reset and re-run that layer
     * so every mounted surface receives the newly computed result. A lyrics-response clear drops
     * only the current track's cached response and canonical source, then reloads that track
     * from providers; the rest of the cached library is left intact.
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
            case AI:
                AIPaidArtifactCache.clear(context);
                secondaryProcessing.cancelActive();
                if (session != null) {
                    session = session.withLayer(LayerKind.MEANING, LayerState.absent(LayerKind.MEANING));
                    session = session.withLayer(LayerKind.SOUND, LayerState.absent(LayerKind.SOUND));
                }
                if (document != null) {
                    document = canonicalSource == null ? LyricsDocument.copyOf(document)
                            : LegacyDocumentComposer.compose(canonicalSource, session);
                    notifyDocument(snapshot(), document);
                }
                break;
            case LYRICS_RESPONSE:
                String currentUri = policy.trackUri();
                if (!currentUri.isEmpty()) {
                    LyricsResponseCache.remove(context,
                            com.eza.spicyex.lyrics.LyricUtils.trackIdFromUri(currentUri));
                    CanonicalSourceCache.remove(context, currentUri);
                }
                reloadCurrentSource();
                break;
        }
    }

    private void reloadCurrentSource() {
        if (track == null || policy.trackUri().isEmpty()) return;
        fetchCoordinator.invalidate(track);
        loadingUri = "";
        canonicalLoadingUri = "";
        // An explicit reload bypasses the error backoff: the owner just asked for this track now.
        nextFetchAtMs = 0L;
        document = null;
        canonicalSource = null;
        session = null;
        sourceProbed = false;
        refreshRequested = true;
        detectionArtifact = null;
        awaitingDetection = false;
        detectionSession.cancelActive();
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

    /**
     * Runs shared detection, then starts both derived lanes.
     *
     * <p>The canonical base is published immediately; detection fills per-row results from the
     * durable artifact (or the detector for missing rows), persists the merged record, and only
     * then do Sound and Meaning start. A cached artifact makes this one prefs read, so a cached
     * track never enters the detector and never repeats Japanese analysis.
     */
    private void startDetection(SpotifyTrack requestedTrack, LyricsDocument snapshot,
                                int requestedGeneration) {
        if (session == null || session.base.isEmpty()) {
            startSharedProcessing(requestedTrack, snapshot, requestedGeneration);
            return;
        }
        final CanonicalBase base = session.base;
        final LyricsDocument requestedDocument = snapshot;
        final String requestedUri = requestedTrack == null ? "" : requestedTrack.uri;
        awaitingDetection = true;
        detectionSession.start(base, providerTextsOf(snapshot), requestedGeneration,
                (candidateBase, candidateGeneration) -> candidateGeneration == policy.generation()
                        && requestedUri.equals(policy.trackUri())
                        && session != null && session.base.digest.equals(candidateBase.digest),
                (finishedBase, artifact) -> {
                    detectionArtifact = artifact;
                    applyDetection(finishedBase, artifact, requestedDocument);
                    applyDetection(finishedBase, artifact, canonicalSource);
                    // Provider translations resolve only against detection of their own text, so
                    // they are applied once that auxiliary detection exists, not before.
                    LyricsDocumentProcessor.reapplyProviderTranslations(context, requestedDocument);
                    LyricsDocumentProcessor.reapplyProviderTranslations(context, canonicalSource);
                    if (awaitingDetection && requestedDocument == document
                            && requestedGeneration == policy.generation()
                            && requestedUri.equals(policy.trackUri())) {
                        awaitingDetection = false;
                        LyricsDocumentProcessor.recomputePendingFlags(context, requestedDocument);
                        startSharedProcessing(requestedTrack, requestedDocument, requestedGeneration);
                    }
                });
    }

    /** Provider-translation strings on a document, for auxiliary detection. */
    private static java.util.List<String> providerTextsOf(LyricsDocument doc) {
        java.util.List<String> out = new ArrayList<>();
        if (doc == null || doc.lines == null) return out;
        for (com.eza.spicyex.lyrics.LyricsLine line : doc.lines) {
            if (line == null) continue;
            if (!com.eza.spicyex.lyrics.LyricUtils.isBlank(line.providerTranslatedText)) {
                out.add(line.providerTranslatedText);
            }
            if (line.backgroundLines == null) continue;
            for (com.eza.spicyex.lyrics.BackgroundLine background : line.backgroundLines) {
                if (background != null
                        && !com.eza.spicyex.lyrics.LyricUtils.isBlank(background.providerTranslatedText)) {
                    out.add(background.providerTranslatedText);
                }
            }
        }
        return out;
    }

    /** Attaches artifact rows to document lines by canonical row ID; a missing row stays undetected. */
    private static void applyDetection(CanonicalBase base, DetectionArtifact artifact,
                                       LyricsDocument target) {
        if (base == null || artifact == null || target == null) return;
        for (com.eza.spicyex.lyrics.session.CanonicalRow row : base.rows) {
            if (row == null || row.index < 0 || row.index >= target.lines.size()) continue;
            com.eza.spicyex.lyrics.LyricsLine line = target.lines.get(row.index);
            if (line != null) line.detection = artifact.result(row.rowId);
        }
    }

    private void startSharedProcessing(SpotifyTrack requestedTrack, LyricsDocument snapshot,
                                       int requestedGeneration) {
        startSharedProcessing(requestedTrack, snapshot, requestedGeneration,
                java.util.Collections.<LayerKind>emptySet());
    }

    private java.util.Set<LayerKind> startSharedProcessing(
            SpotifyTrack requestedTrack, LyricsDocument snapshot, int requestedGeneration,
            java.util.Set<LayerKind> explicitAiRequests) {
        LyricsRenderConfig config = renderConfig();
        com.eza.spicyex.lyrics.session.SoundArtifact displayedSound = session != null
                && session.sound.artifact instanceof com.eza.spicyex.lyrics.session.SoundArtifact
                ? (com.eza.spicyex.lyrics.session.SoundArtifact) session.sound.artifact : null;
        java.util.Set<LayerKind> started = secondaryProcessing.start(snapshot.trackId, requestedGeneration, snapshot,
                config.transliterationEnabled, romanizationOptions(config),
                displayedSound,
                explicitAiRequests,
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
                                                   com.eza.spicyex.lyrics.session.LayerFailure failure,
                                                   LyricsDocument processed, String message, int changed) {
                        adoptLayerArtifact(layer, artifact, failure, processed, requestedGeneration);
                        publishProcessed(processed, requestedGeneration);
                    }
                });
        markLanesRunning(started, snapshot, config, explicitAiRequests);
        // The initial document was published before the asynchronous lanes were started. Publish
        // the processing transition too, otherwise surfaces never see the pending state and their
        // chip indicators remain idle for the entire AI/network request.
        if (!started.isEmpty()) publishProcessed(snapshot, requestedGeneration);
        return started;
    }

    /**
     * Marks the layers whose lanes actually began work as running.
     *
     * <p>A completion callback cannot express this: a layer with nothing to do also completes. The
     * distinction is what the surfaces' progress indicators read, and having it on the session is
     * what lets publication stop reading flags off the mutable document.
     */
    private void markLanesRunning(java.util.Set<LayerKind> started, LyricsDocument snapshot,
                                  LyricsRenderConfig config,
                                  java.util.Set<LayerKind> explicitAiRequests) {
        if (session == null || started.isEmpty()) return;
        com.eza.spicyex.lyrics.ai.AiSettings aiSettings =
                new com.eza.spicyex.lyrics.ai.AiSettings(context);
        for (LayerKind layer : started) {
            LayerState state = session.layer(layer);
            boolean explicitAi = explicitAiRequests != null && explicitAiRequests.contains(layer);
            boolean automaticAi = aiSettings.canRequest() && (layer == LayerKind.SOUND
                    ? aiSettings.pronunciationAutomatic() : aiSettings.translationAutomatic());
            session = session.withLayer(layer, state.processing(
                    explicitAi || automaticAi ? LayerAuthority.AI
                            : layer == LayerKind.SOUND
                            ? LayerAuthority.DETERMINISTIC : LayerAuthority.MACHINE,
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
                                    com.eza.spicyex.lyrics.session.LayerFailure failure,
                                    LyricsDocument processed, int requestedGeneration) {
        if (session == null || processed != document || requestedGeneration != policy.generation()) return;
        LayerState state = session.layer(layer);
        session = session.withLayer(layer, state.settled(artifact, failure));
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
     * <p>Always returns a document the caller owns. The composer already builds a fresh projection,
     * so the common path costs one document instead of a compose plus a defensive copy; only the
     * fallback path copies. The fallback below returns the raw document if composition ever throws
     * — that would publish original lyrics without readings or translations, which is degraded but
     * honest, and {@code COMPOSED_PROJECTION_MISMATCH} records it.
     */
    private LyricsDocument publishedProjection(LyricsDocument value) {
        if (value == null) return null;
        if (session == null || canonicalSource == null) return LyricsDocument.copyOf(value);
        try {
            LyricsDocument composed = LegacyDocumentComposer.compose(canonicalSource, session);
            if (composed == null || composed.lines.size() != value.lines.size()) {
                return LyricsDocument.copyOf(value);
            }
            return composed;
        } catch (Throwable t) {
            LyricPipelineMetrics.increment(LyricPipelineMetrics.Counter.COMPOSED_PROJECTION_MISMATCH);
            return LyricsDocument.copyOf(value);
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
