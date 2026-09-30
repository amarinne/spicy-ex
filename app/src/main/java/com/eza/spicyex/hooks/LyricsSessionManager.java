package com.eza.spicyex.hooks;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.eza.spicyex.SpotifyPlusConfig;
import com.eza.spicyex.SpotifyTrack;
import com.eza.spicyex.Diagnostics;
import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.lyrics.processing.LyricsDocumentProcessor;
import com.eza.spicyex.lyrics.providers.LyricsFetchDiagnosticsState;
import com.eza.spicyex.lyrics.LyricsRenderConfig;
import com.eza.spicyex.lyrics.processing.LyricsSecondaryProcessingSession;
import com.eza.spicyex.lyrics.processing.LyricsSecondaryProcessor;
import com.eza.spicyex.lyrics.language.RomanizationOptions;
import com.eza.spicyex.lyrics.session.CanonicalBase;
import com.eza.spicyex.lyrics.session.CanonicalBaseAdoption;
import com.eza.spicyex.lyrics.session.CanonicalSourceCache;
import com.eza.spicyex.lyrics.session.AIPaidArtifactCache;
import com.eza.spicyex.lyrics.session.DetectionArtifact;
import com.eza.spicyex.lyrics.session.LyricsDetectionSession;
import com.eza.spicyex.lyrics.session.LyricsMemoryPressure;
import com.eza.spicyex.lyrics.providers.LyricsResponseCache;
import com.eza.spicyex.lyrics.cache.CacheClearKind;
import com.eza.spicyex.lyrics.cache.LyricCaches;
import com.eza.spicyex.lyrics.session.LyricPipelineMetrics;
import com.eza.spicyex.lyrics.session.DerivedLayerArtifact;
import com.eza.spicyex.lyrics.session.LayerAuthority;
import com.eza.spicyex.lyrics.session.LayerKind;
import com.eza.spicyex.lyrics.session.LayerState;
import com.eza.spicyex.lyrics.session.LayerStatus;
import com.eza.spicyex.lyrics.session.LegacyDocumentComposer;
import com.eza.spicyex.lyrics.session.LyricSession;
import com.eza.spicyex.lyrics.session.LyricsSourcePreferences;
import com.eza.spicyex.lyrics.session.MeaningArtifact;
import com.eza.spicyex.lyrics.providers.NativeLyricsSource;
import com.eza.spicyex.lyrics.catalog.AcquisitionPlanner;
import com.eza.spicyex.lyrics.catalog.AcquisitionScope;
import com.eza.spicyex.lyrics.catalog.CatalogAdapters;
import com.eza.spicyex.lyrics.catalog.CatalogDecisions;
import com.eza.spicyex.lyrics.catalog.CatalogPickerModel;
import com.eza.spicyex.lyrics.catalog.CatalogPolicy;
import com.eza.spicyex.lyrics.catalog.CatalogResolver;
import com.eza.spicyex.lyrics.catalog.CatalogSource;
import com.eza.spicyex.lyrics.catalog.CatalogState;
import com.eza.spicyex.lyrics.catalog.CatalogStore;
import com.eza.spicyex.lyrics.catalog.LyricsCatalog;
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
        /** A null document withdraws the rendered base for this same track and generation. */
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
    private final Handler handler;
    private final java.util.function.LongSupplier snapshotClock;
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
    /** Candidate currently rendered; empty for a delivery the catalog could not store. */
    private String displayedCandidateId = "";
    /** Newest catalog view applied here; views read earlier on another IO thread are stale. */
    private long appliedViewSequence;
    /** Source policy the applied view was planned under; Save reconciles only on a change. */
    private CatalogPolicy loadedPolicy;
    /** This visit's next automatic fetch; null once the catalog plan settled the visit. */
    private AcquisitionPlanner.Plan pendingPlan;
    private boolean replanning;
    /** Spotify native (local, free) was already tried during this visit. */
    private boolean localTried;
    private int autoAttempts;
    /** Why the current visit shows no lyrics; answers new requests without another fetch. */
    private String noLyricsReason = "";
    /** Hard cap on automatic fetches per visit, whatever the stored outcomes say. */
    static final int MAX_AUTO_ATTEMPTS = 3;
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
        this.handler = new Handler(Looper.getMainLooper());
        this.snapshotClock = SystemClock::elapsedRealtime;
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

    /** Injects runtime dependencies for JVM tests of session transitions and publication. */
    LyricsSessionManager(Context context, Handler handler,
                         LyricsSecondaryProcessingSession secondaryProcessing,
                         LyricsDetectionSession detectionSession,
                         java.util.function.LongSupplier snapshotClock) {
        this.hook = null;
        this.fetchCoordinator = null;
        this.context = context;
        this.handler = handler;
        this.secondaryProcessing = secondaryProcessing;
        this.detectionSession = detectionSession;
        this.snapshotClock = snapshotClock;
    }

    void start() {
        if (started) return;
        started = true;
        // Native-first: a hook capture for the current track publishes without waiting for a
        // poll or a fetch retry window. Stale-track captures stay in the native memory cache;
        // generation and adoption guards below drop them from the screen.
        try {
            fetchCoordinator.nativeLyricsSource().addNativeListener(nativeCaptureListener);
        } catch (Throwable ignored) {
        }
    }

    private final NativeLyricsSource.NativeListener nativeCaptureListener = this::postNativeCapture;

    private void postNativeCapture(String trackId, LyricsDocument captured) {
        try {
            handler.post(() -> acceptNativeCapture(trackId, captured));
        } catch (Throwable ignored) {
        }
    }

    /** Commits a native capture for the current track, then publishes the seat it produces. */
    private void acceptNativeCapture(String trackId, LyricsDocument captured) {
        SpotifyTrack current = track;
        if (current == null || captured == null || trackId == null || trackId.isEmpty()) return;
        String bare = CatalogSource.bareTrackId(current.uri);
        if (bare.isEmpty() || !bare.equals(trackId)) return;
        acceptProviderResult(current, policy.trackUri(), policy.generation(), captured, null);
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
        if ("no_lyrics".equals(status) && canonicalLoadingUri.isEmpty()
                && !policy.trackUri().equals(loadingUri)
                && (pendingPlan == null || !pendingPlan.fetches())) {
            // The visit already settled without lyrics; a new surface gets the same answer.
            callback.onError(noLyricsReason);
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
        displayedCandidateId = "";
        pendingPlan = null;
        replanning = false;
        localTried = false;
        autoAttempts = 0;
        noLyricsReason = "";
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

    /**
     * Catalog-first entry point. The stored seat renders before any derived processing and without
     * a source request; the acquisition plan read from the same committed state then decides
     * whether any provider is asked during this visit.
     */
    private void loadCanonicalBase(String requestedUri, int requestedGeneration) {
        if (requestedUri.isEmpty() || requestedUri.equals(canonicalLoadingUri)) return;
        canonicalLoadingUri = requestedUri;
        final SpotifyTrack requestedTrack = track;
        final long startedAtMs = SystemClock.elapsedRealtime();
        NativeRuntime.LYRICS_IO.execute(() -> {
            LyricsCatalog.View view = null;
            try {
                probeNativeLyrics(requestedTrack);
                view = LyricsCatalog.load(context, requestedTrack, true);
                finalizeSeat(view);
            } catch (Throwable t) {
                // An unreadable record must never strand the session: the fetch path still runs.
                view = null;
            }
            final LyricsCatalog.View loaded = view;
            handler.post(() -> acceptCatalogLoad(requestedTrack, requestedUri, requestedGeneration,
                    loaded, startedAtMs));
        });
    }

    /**
     * A local hit commits immediately. A miss asks Spotify's own lyrics client for this
     * track; only its confirmed absence records NOT_FOUND. Manual pins never move.
     * Globally disabled Spotify stays untouched except for an explicit picker check.
     */
    private void probeNativeLyrics(SpotifyTrack track) {
        if (track == null || context == null || fetchCoordinator == null) return;
        try {
            if (!CatalogPolicy.read(context).enabled(CatalogSource.SourceId.SPOTIFY_NATIVE)) {
                return;
            }
            LyricsDocument nativeDoc =
                    fetchCoordinator.nativeLyricsSource().getNativeLyricsDocument(track);
            if (nativeDoc != null && nativeDoc.lines != null && !nativeDoc.lines.isEmpty()) {
                CatalogAdapters.recordSuccess(context, CatalogSource.SourceId.SPOTIFY_NATIVE,
                        track, nativeDoc, CatalogSource.MatchMethod.EXACT_SPOTIFY_ID,
                        com.eza.spicyex.lyrics.LyricUtils.trackIdFromUri(
                                track == null ? "" : track.uri),
                        "", CatalogAdapters.SPOTIFY_NATIVE_ADAPTER_REVISION);
            } else {
                // Spotify's player may never mount its lyrics surface during a fullscreen
                // track change. Ask its authenticated lyrics client for this track directly.
                fetchCoordinator.nativeLyricsSource().requestNativeLyrics(track, (result, error) -> {
                    if (result == null && "Spotify has no lyrics for this track".equals(error)) {
                        CatalogAdapters.recordError(context, CatalogSource.SourceId.SPOTIFY_NATIVE,
                                track, error);
                    }
                });
            }
        } catch (Throwable ignored) {
        }
    }

    /** Reproduces exactly what a fresh parse produces for a stored seat document. */
    private void finalizeSeat(LyricsCatalog.View view) {
        if (view == null || view.document == null) return;
        LyricsDocumentProcessor.finalizeParsedDocument(context, view.document,
                NativeRuntime.GOOGLE_PROCESSING_VERSION);
    }

    private void acceptCatalogLoad(SpotifyTrack requestedTrack, String requestedUri,
                                   int requestedGeneration, LyricsCatalog.View view,
                                   long startedAtMs) {
        if (!requestedUri.equals(canonicalLoadingUri)) return;
        canonicalLoadingUri = "";
        if (!policy.accepts(requestedGeneration, requestedUri)) {
            LyricPipelineMetrics.increment(LyricPipelineMetrics.Counter.STALE_RESULT_REJECTED);
            return;
        }
        if (view != null && !applyView(view)) {
            // A delivery (a native capture, usually) committed and published while this load was
            // reading; plan from the newer state instead of this snapshot.
            replan();
            return;
        }
        if (view != null && view.document != null
                && publishSeat(requestedTrack, requestedUri, requestedGeneration, view.document,
                view.sourceRevision)) {
            LyricsFetchDiagnosticsState.recordCached(view.document);
            LyricPipelineMetrics.increment(LyricPipelineMetrics.Counter.CACHED_ORIGINAL_RENDER);
            LyricPipelineMetrics.record(LyricPipelineMetrics.Timing.CACHED_ORIGINAL_RENDER,
                    SystemClock.elapsedRealtime() - startedAtMs);
        }
        schedule(view, false);
        if (document == null && (pendingPlan == null || !pendingPlan.fetches())) {
            // Nothing stored renders and the plan asks nobody now: answer waiting surfaces.
            reportNoLyrics(requestedGeneration, view == null ? "Lyrics unavailable"
                    : settledReason(view.plan));
        }
        maybeFetch();
    }

    private void reportNoLyrics(int requestedGeneration, String reason) {
        status = "no_lyrics";
        noLyricsReason = reason == null ? "Lyrics unavailable" : reason;
        for (RequestRecord request : takeRequests(requestedGeneration)) {
            request.callback.onError(reason);
        }
        notifyState(snapshot());
    }

    private static String settledReason(AcquisitionPlanner.Plan plan) {
        if (plan != null && "all-sources-disabled".equals(plan.reason)) {
            return "All lyric sources disabled";
        }
        // "cached no-result" classifies as a durable miss, so surfaces stop re-asking.
        return "Lyrics unavailable (cached no-result)";
    }

    /** Drops catalog views read before one already applied: IO threads can post out of order. */
    private boolean applyView(LyricsCatalog.View view) {
        if (view == null || view.sequence < appliedViewSequence) return false;
        appliedViewSequence = view.sequence;
        loadedPolicy = view.policy;
        return true;
    }

    /**
     * Arms this visit's next automatic fetch from a catalog plan. After an attempt, a source that
     * is still due waits the transient base delay, so an outcome no adapter recorded cannot loop.
     * Tracks without a catalog identity (episodes, local files) get one attempt; the repository
     * reports why they have no lyrics.
     */
    private void schedule(LyricsCatalog.View view, boolean afterAttempt) {
        long now = SystemClock.elapsedRealtime();
        if (view == null) {
            pendingPlan = afterAttempt || localTried ? null
                    : AcquisitionPlanner.refreshAll(CatalogPolicy.read(context));
            nextFetchAtMs = now;
            return;
        }
        AcquisitionPlanner.Plan plan = view.plan;
        if (plan.fetches()) {
            pendingPlan = plan;
            nextFetchAtMs = afterAttempt ? now + AcquisitionPlanner.TRANSIENT_BASE_RETRY_MS : now;
        } else if (plan.retryAtMs > 0L) {
            // Nothing is due yet: re-plan from stored state once the earliest source is.
            pendingPlan = plan;
            nextFetchAtMs = now + Math.max(0L, plan.retryAtMs - System.currentTimeMillis());
        } else {
            pendingPlan = null;
        }
    }

    private void clearCurrent() {
        adoptTrack(null);
    }

    private void maybeFetch() {
        if (track == null || policy.trackUri().isEmpty()
                || policy.trackUri().equals(loadingUri)
                // The stored seat may still resolve; never race it to the network.
                || !canonicalLoadingUri.isEmpty()
                // The plan settled this visit: no source is due.
                || pendingPlan == null
                || SystemClock.elapsedRealtime() < nextFetchAtMs) return;
        if (!pendingPlan.fetches()) {
            replan();
            return;
        }
        if (autoAttempts >= MAX_AUTO_ATTEMPTS) {
            pendingPlan = null;
            return;
        }
        final AcquisitionScope scope = pendingPlan.scope;
        final AcquisitionPlanner.Plan launched = pendingPlan;
        final SpotifyTrack requestedTrack = track;
        final String requestedUri = policy.trackUri();
        final int requestedGeneration = policy.generation();
        pendingPlan = null;
        autoAttempts++;
        localTried = true;
        loadingUri = requestedUri;
        LyricPipelineMetrics.increment(LyricPipelineMetrics.Counter.SOURCE_FETCH_CALL);
        if (document == null) {
            status = "loading";
            notifyState(snapshot());
        }
        try {
            fetchAutomaticSources(requestedTrack, requestedUri, requestedGeneration, scope);
        } catch (Throwable launchFailed) {
            // A fetch that never starts must not keep the fetch gate armed: that strands the
            // session on stale rows with every later maybeFetch declining to run.
            NativeSpicyLyricsHook.dbg("maybeFetch",
                    "fetch launch failed: " + launchFailed.getClass().getSimpleName());
            loadingUri = "";
            pendingPlan = launched;
            nextFetchAtMs = SystemClock.elapsedRealtime() + RETRY_MS;
        }
    }

    /** Auto asks every due configured source; the catalog ranks their stored results. */
    private void fetchAutomaticSources(SpotifyTrack requestedTrack, String requestedUri,
                                       int requestedGeneration, AcquisitionScope scope) {
        if (scope.sourceOrderMode) {
            fetchCoordinator.fetchLyrics(context, requestedTrack, requestedGeneration, scope,
                    automaticResult(requestedTrack, requestedUri, requestedGeneration));
            return;
        }
        java.util.concurrent.atomic.AtomicInteger remaining =
                new java.util.concurrent.atomic.AtomicInteger(scope.sources.size());
        java.util.concurrent.atomic.AtomicBoolean delivered =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        NativeSpicyLyricsHook.LyricsResultCallback result =
                new NativeSpicyLyricsHook.LyricsResultCallback() {
                    @Override public void onSuccess(LyricsDocument document) {
                        delivered.set(true);
                        acceptProviderResult(requestedTrack, requestedUri, requestedGeneration,
                                document, null);
                        remaining.decrementAndGet();
                    }

                    @Override public void onError(String error) {
                        if (remaining.decrementAndGet() == 0 && !delivered.get()) {
                            handler.post(() -> acceptError(requestedTrack, requestedUri,
                                    requestedGeneration, error));
                        }
                    }
                };
        for (CatalogSource.SourceId source : scope.sources) {
            fetchCoordinator.fetchCatalogSource(context, requestedTrack, requestedGeneration,
                    source, result);
        }
    }

    private NativeSpicyLyricsHook.LyricsResultCallback automaticResult(
            SpotifyTrack requestedTrack, String requestedUri, int requestedGeneration) {
        return new NativeSpicyLyricsHook.LyricsResultCallback() {
            @Override public void onSuccess(LyricsDocument result) {
                acceptProviderResult(requestedTrack, requestedUri, requestedGeneration,
                        result, null);
            }

            @Override public void onError(String error) {
                handler.post(() -> acceptError(requestedTrack, requestedUri,
                        requestedGeneration, error));
            }
        };
    }

    /** Re-reads the stored state once a held-back source becomes due, then plans again. */
    private void replan() {
        if (replanning || track == null) return;
        replanning = true;
        final SpotifyTrack requestedTrack = track;
        final String requestedUri = policy.trackUri();
        final int requestedGeneration = policy.generation();
        final boolean includeLocal = !localTried;
        NativeRuntime.LYRICS_IO.execute(() -> {
            LyricsCatalog.View view = null;
            try {
                view = LyricsCatalog.current(context, requestedTrack, includeLocal);
            } catch (Throwable ignored) {
            }
            final LyricsCatalog.View planned = view;
            handler.post(() -> {
                replanning = false;
                if (!policy.accepts(requestedGeneration, requestedUri)) return;
                if (planned == null) {
                    pendingPlan = null;
                    return;
                }
                schedule(planned, false);
                maybeFetch();
            });
        });
    }

    /**
     * Publishes the catalog seat. The catalog already decided it, so there is no quality gate
     * here: a manual pick, an Auto upgrade, and a restart all render exactly what the seat names.
     * Canonical identity still decides invalidation: an equal digest keeps every derived artifact.
     *
     * @return false when nothing changed on screen (same candidate, stale track, or empty rows)
     */
    private boolean publishSeat(SpotifyTrack requestedTrack, String requestedUri,
                                int requestedGeneration, LyricsDocument result, int sourceRevision) {
        if (result == null || result.lines.isEmpty()) return false;
        if (!policy.accepts(requestedGeneration, requestedUri)) {
            LyricPipelineMetrics.increment(LyricPipelineMetrics.Counter.STALE_RESULT_REJECTED);
            return false;
        }
        String candidateId = result.catalogCandidateId == null ? "" : result.catalogCandidateId;
        if (document != null && !candidateId.isEmpty() && candidateId.equals(displayedCandidateId)) {
            return false;
        }
        displayedCandidateId = candidateId;
        CanonicalBase incoming = CanonicalBase.fromDocument(requestedUri, result);
        CanonicalBaseAdoption.Outcome outcome = CanonicalBaseAdoption.evaluate(
                session != null, session == null ? "" : session.identity.canonicalDigest,
                incoming.digest);
        if (outcome == CanonicalBaseAdoption.Outcome.UNCHANGED && document != null) {
            // Another candidate with identical canonical content: publish its provider metadata
            // but keep the source revision and every digest-bound derived artifact.
            document = result;
            canonicalSource = LyricsDocument.copyOf(result);
            status = "ready";
            syncDocumentLayerFlags();
            notifyDocument(snapshot(), result);
            return true;
        }
        // withReplacedBase increments the source revision and drops artifacts tied to the old
        // digest, which is the whole invalidation axis for a source change.
        session = session == null
                ? LyricSession.of(incoming, requestedGeneration, sourceRevision)
                : session.withReplacedBase(incoming);
        document = result;
        canonicalSource = LyricsDocument.copyOf(result);
        status = "ready";
        LyricPipelineMetrics.increment(LyricPipelineMetrics.Counter.FRESH_SOURCE_ACQUIRED);
        if (outcome == CanonicalBaseAdoption.Outcome.REPLACE) {
            LyricPipelineMetrics.increment(LyricPipelineMetrics.Counter.SOURCE_REPLACED);
        }
        Snapshot snapshot = snapshot();
        for (RequestRecord request : takeRequests(requestedGeneration)) {
            request.callback.onSuccess(LyricsDocument.copyOf(result));
        }
        notifyDocument(snapshot, result);
        startDetection(requestedTrack, result, requestedGeneration);
        return true;
    }

    /**
     * Provider deliveries are outcomes, not display decisions. Each is committed to the catalog
     * (adapters stamp what they stored; anything else is committed here), then the seat is re-read
     * and published only when it changed. A manual pin therefore never moves, and callback order
     * cannot flip an equal-ranked incumbent.
     */
    private void acceptProviderResult(SpotifyTrack requestedTrack, String requestedUri,
                                      int requestedGeneration, LyricsDocument result,
                                      LyricsHost.CatalogActionCallback callback) {
        if (result == null || result.lines.isEmpty()) {
            // An empty success is a failed fetch, not a document.
            handler.post(() -> {
                acceptError(requestedTrack, requestedUri, requestedGeneration, "empty result");
                completeCatalogAction(callback, false, "No lyrics returned");
            });
            return;
        }
        NativeRuntime.LYRICS_IO.execute(() -> {
            boolean stored = false;
            LyricsCatalog.View view = null;
            try {
                stored = CatalogAdapters.commitDelivered(context, requestedTrack, result);
                view = LyricsCatalog.current(context, requestedTrack, false);
                finalizeSeat(view);
            } catch (Throwable error) {
                NativeSpicyLyricsHook.dbg("acceptProviderResult",
                        "catalog commit failed: " + error.getClass().getSimpleName());
            }
            final boolean durable = stored;
            final LyricsCatalog.View seated = view;
            // A stored delivery may still be ineligible for Auto. Preserve the adapter's
            // provenance and judge fallback against the same state as the seat.
            final boolean refused = seated == null
                    ? CatalogAdapters.isRefusedFallback(context, requestedTrack, result)
                    : CatalogAdapters.isRefusedFallback(seated.policy, seated.state,
                            seated.trackId, result);
            handler.post(() -> acceptProviderView(requestedTrack, requestedUri,
                    requestedGeneration, result, callback, durable, seated, refused));
        });
    }

    /** Applies provider completion only while its catalog snapshot is still current. */
    void acceptProviderView(SpotifyTrack requestedTrack, String requestedUri,
                            int requestedGeneration, LyricsDocument result,
                            LyricsHost.CatalogActionCallback callback, boolean durable,
                            LyricsCatalog.View seated, boolean refused) {
        if (!policy.accepts(requestedGeneration, requestedUri)) {
            LyricPipelineMetrics.increment(
                    LyricPipelineMetrics.Counter.STALE_RESULT_REJECTED);
            completeCatalogAction(callback, false, "Track changed");
            return;
        }
        if (seated != null && !applyView(seated)) {
            completeCatalogAction(callback, durable || document != null, "Source checked; newer state retained");
            return;
        }
        if (requestedUri.equals(loadingUri)) loadingUri = "";
        if (seated != null && seated.document != null) {
            publishSeat(requestedTrack, requestedUri, requestedGeneration,
                    seated.document, seated.sourceRevision);
        } else if (document == null && !refused) {
            // Nothing renders from the catalog (a failed write, or a source Auto may not
            // use): show the delivery for this visit without claiming it is stored.
            publishSeat(requestedTrack, requestedUri, requestedGeneration, result, 1);
        } else if (document == null) {
            acceptError(requestedTrack, requestedUri, requestedGeneration,
                    "Source not eligible for this track");
        }
        completeCatalogAction(callback, durable || document != null,
                durable ? "Source checked"
                        : refused ? "Source not used" : "Source checked; not saved");
    }

    // --- Catalog source-picker operations ---
    //
    // Every op is one catalog command: selecting stored data makes zero requests, and every render
    // publishes the committed seat so Sound/Meaning invalidation and all surfaces stay consistent.
    // Results post back to the handler thread.

    /** Picker rows for the current track, resolved off-thread and posted back. */
    void pickerRows(LyricsHost.CatalogPickerRowsCallback callback) {
        String uri = policy.trackUri();
        if (callback == null) return;
        if (track == null || uri.isEmpty()) {
            handler.post(() -> callback.onRows(uri, java.util.Collections.emptyList()));
            return;
        }
        NativeRuntime.LYRICS_IO.execute(() -> {
            try {
                String bare = CatalogSource.bareTrackId(uri);
                CatalogState state = CatalogStore.state(context, bare);
                CatalogPolicy sources = CatalogPolicy.read(context);
                CatalogResolver.Resolution auto = state.candidates.isEmpty() ? null
                        : CatalogDecisions.autoResolution(state, sources);
                java.util.List<CatalogPickerModel.Row> rows = CatalogPickerModel.build(
                        state.candidates, state.statuses(), state.selection, auto, sources);
                long trackBytes = bare.isEmpty() ? 0L
                        : CatalogStore.payloadBytesForTrack(context, bare);
                java.util.List<CatalogPickerModel.Row> displayed = new java.util.ArrayList<>();
                for (CatalogPickerModel.Row row : rows) {
                    displayed.add(row.kind == CatalogPickerModel.RowKind.ACTION_DELETE_TRACK
                            ? row.withSubtitle(com.eza.spicyex.lyrics.catalog.CatalogStorage
                                    .formatBytes(trackBytes) + " stored on device")
                            : row);
                }
                handler.post(() -> {
                    try {
                        callback.onRows(uri, java.util.Collections.unmodifiableList(displayed));
                    } catch (Throwable ignored) {
                    }
                });
            } catch (Throwable ignored) {
                handler.post(() -> callback.onRows(uri, java.util.Collections.emptyList()));
            }
        });
    }

    String catalogTrackUri() {
        return policy.trackUri();
    }

    /** The acquisition fetch owns {@code loadingUri} from launch until its outcome is accepted. */
    boolean catalogFetchInFlight() {
        return !loadingUri.isEmpty() && loadingUri.equals(policy.trackUri());
    }

    /** Pins one stored candidate as this track's manual selection and renders it. */
    void selectCatalogCandidate(String candidateId, LyricsHost.CatalogActionCallback callback) {
        if (candidateId == null || candidateId.isEmpty()) {
            completeCatalogAction(callback, false, "No current track or candidate");
            return;
        }
        runCatalogCommand(LyricsCatalog.select(candidateId), callback, "Source selected");
    }

    /** Drops the manual pin and re-elects the automatic winner from stored candidates. */
    void resetCatalogToAuto(LyricsHost.CatalogActionCallback callback) {
        runCatalogCommand(LyricsCatalog.resetAuto(), callback, "Source selected");
    }

    /** Rejects a wrong match: that provider item is removed and refused from now on. */
    void rejectCatalogCandidate(String candidateId, LyricsHost.CatalogActionCallback callback) {
        if (candidateId == null || candidateId.isEmpty()) {
            completeCatalogAction(callback, false, "Missing candidate");
            return;
        }
        runCatalogCommand(LyricsCatalog.reject(candidateId), callback, "Rejected match");
    }

    /** Deletes one saved candidate; a deleted pin returns the track to Auto in the same commit. */
    void removeCatalogCandidate(String candidateId, LyricsHost.CatalogActionCallback callback) {
        if (candidateId == null || candidateId.isEmpty()) {
            completeCatalogAction(callback, false, "Missing candidate");
            return;
        }
        runCatalogCommand(LyricsCatalog.remove(candidateId), callback,
                "Removed saved candidate");
    }

    /**
     * Runs one user command as one catalog transaction, then publishes the committed seat. When
     * the command leaves nothing to render, the track re-enters acquisition from the new state.
     */
    private void runCatalogCommand(LyricsCatalog.Command command,
                                   LyricsHost.CatalogActionCallback callback, String success) {
        SpotifyTrack current = track;
        String uri = policy.trackUri();
        int generation = policy.generation();
        if (current == null || uri.isEmpty()) {
            completeCatalogAction(callback, false, "No current track");
            return;
        }
        NativeRuntime.LYRICS_IO.execute(() -> {
            LyricsCatalog.View view = null;
            try {
                view = LyricsCatalog.command(context, current, command);
                finalizeSeat(view);
            } catch (Throwable error) {
                NativeSpicyLyricsHook.dbg("runCatalogCommand",
                        "failed: " + error.getClass().getSimpleName());
            }
            final LyricsCatalog.View committed = view;
            handler.post(() -> {
                if (committed == null || !committed.durable) {
                    completeCatalogAction(callback, false, commandFailure(committed));
                    return;
                }
                if (!policy.accepts(generation, uri)) {
                    completeCatalogAction(callback, true, success);
                    return;
                }
                if (committed.document != null) {
                    if (applyView(committed)) {
                        publishSeat(current, uri, generation, committed.document,
                                committed.sourceRevision);
                    }
                } else {
                    clearCurrent();
                    adoptTrack(current);
                }
                completeCatalogAction(callback, true, success);
            });
        });
    }

    private static String commandFailure(LyricsCatalog.View view) {
        if (view == null) return "Unsupported track";
        switch (view.outcome) {
            case "missing-candidate":
                return "Saved source is unavailable";
            case "storage-failed":
                return "Could not save source selection";
            default:
                return view.outcome.isEmpty() ? "Could not save source selection" : view.outcome;
        }
    }

    /** Explicit per-source check. The selected row never falls through to another provider. */
    void refreshCatalogSource(CatalogSource.SourceId source,
                              LyricsHost.CatalogActionCallback callback) {
        SpotifyTrack current = track;
        String uri = policy.trackUri();
        int generation = policy.generation();
        if (current == null || uri.isEmpty() || source == null) {
            completeCatalogAction(callback, false, "No current track or source");
            return;
        }
        try {
            fetchCoordinator.fetchCatalogSource(context, current, generation, source,
                    new NativeSpicyLyricsHook.LyricsResultCallback() {
                        @Override public void onSuccess(LyricsDocument result) {
                            acceptProviderResult(current, uri, generation, result, callback);
                        }

                        @Override public void onError(String error) {
                            // F9: the strict source already recorded its terminal outcome.
                            // Recording here counted provider-owned failures twice and told
                            // nothing new about the retry policy.
                            completeCatalogAction(callback, false, error);
                        }
                    });
        } catch (Throwable t) {
            completeCatalogAction(callback, false, t.getMessage());
        }
    }

    /** Explicitly checks every enabled source, in preference order. */
    void refreshAllCatalogSourcesInOrder(LyricsHost.CatalogActionCallback callback) {
        SpotifyTrack current = track;
        String uri = policy.trackUri();
        int generation = policy.generation();
        if (current == null || uri.isEmpty()) {
            completeCatalogAction(callback, false, "No current track");
            return;
        }
        java.util.List<CatalogSource.SourceId> sources =
                CatalogPolicy.read(context).enabledOrder;
        if (sources.isEmpty()) {
            completeCatalogAction(callback, false, "All lyric sources disabled");
            return;
        }
        checkCatalogSourcesInOrder(sources, 0, uri, generation, 0, callback);
    }

    private void checkCatalogSourcesInOrder(java.util.List<CatalogSource.SourceId> sources,
            int index, String uri, int generation, int found,
            LyricsHost.CatalogActionCallback callback) {
        if (!policy.accepts(generation, uri)) {
            completeCatalogAction(callback, false, "Track changed");
            return;
        }
        if (index == sources.size()) {
            completeCatalogAction(callback, true,
                    "Checked " + sources.size() + " sources; " + found + " available");
            return;
        }
        CatalogSource.SourceId source = sources.get(index);
        if (callback != null) callback.onProgress("Checking "
                + CatalogPickerModel.displaySource(source) + " (" + (index + 1)
                + "/" + sources.size() + ")");
        refreshCatalogSource(source, (success, detail) -> {
            if (callback != null) callback.onProgress(
                    CatalogPickerModel.displaySource(source) + ": "
                            + (success ? "Available" : (detail == null ? "Failed" : detail)));
            checkCatalogSourcesInOrder(sources, index + 1, uri, generation,
                    found + (success ? 1 : 0), callback);
        });
    }

    /** Runs both explicit-check adapters; each commits and the seat decides what renders. */
    void checkOtherCatalogSources(LyricsHost.CatalogActionCallback callback) {
        if (track == null || policy.trackUri().isEmpty()) {
            completeCatalogAction(callback, false, "No current track");
            return;
        }
        java.util.concurrent.atomic.AtomicInteger remaining =
                new java.util.concurrent.atomic.AtomicInteger(2);
        java.util.concurrent.atomic.AtomicBoolean anySuccess =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        LyricsHost.CatalogActionCallback one = (success, detail) -> {
            if (success) anySuccess.set(true);
            if (remaining.decrementAndGet() == 0) {
                completeCatalogAction(callback, anySuccess.get(),
                        anySuccess.get() ? "Other sources checked" : detail);
            }
        };
        refreshCatalogSource(CatalogSource.SourceId.QQ, one);
        refreshCatalogSource(CatalogSource.SourceId.NETEASE, one);
    }

    /**
     * Deletes every saved row for the current track, including the public release's record and
     * per-track override it would otherwise re-import, then re-enters the miss flow.
     */
    void deleteCatalogTrack(LyricsHost.CatalogActionCallback callback) {
        SpotifyTrack current = track;
        String uri = policy.trackUri();
        int generation = policy.generation();
        if (current == null || uri.isEmpty()) {
            completeCatalogAction(callback, false, "No current track");
            return;
        }
        NativeRuntime.LYRICS_IO.execute(() -> {
            boolean deleted = false;
            try {
                String bare = CatalogSource.bareTrackId(uri);
                if (!bare.isEmpty()) {
                    deleted = CatalogStore.deleteTrack(context, bare) >= 0L;
                    CanonicalSourceCache.remove(context, uri);
                    LyricsSourcePreferences.setTrackOverride(context, bare,
                            (LyricsSourcePreferences.Source) null);
                }
            } catch (Throwable ignored) {
            }
            final boolean ok = deleted;
            handler.post(() -> {
                if (ok && policy.accepts(generation, uri)) {
                    clearCurrent();
                    adoptTrack(current);
                }
                completeCatalogAction(callback, ok,
                        ok ? "Deleted saved lyrics" : "Could not delete saved lyrics");
            });
        });
    }

    /**
     * Settings Save: when the source policy changed, re-seat the current track from stored
     * candidates immediately and plan acquisition under the new policy. No request is made for
     * a seat that already satisfies it.
     */
    void reconcileSources() {
        if (track == null || policy.trackUri().isEmpty() || !canonicalLoadingUri.isEmpty()) return;
        if (CatalogPolicy.read(context).equals(loadedPolicy)) return;
        final SpotifyTrack requestedTrack = track;
        final String requestedUri = policy.trackUri();
        final int requestedGeneration = policy.generation();
        final boolean includeLocal = !localTried;
        NativeRuntime.LYRICS_IO.execute(() -> {
            LyricsCatalog.View view = null;
            try {
                view = LyricsCatalog.load(context, requestedTrack, includeLocal);
                finalizeSeat(view);
            } catch (Throwable ignored) {
            }
            final LyricsCatalog.View reconciled = view;
            handler.post(() -> {
                if (reconciled == null || !policy.accepts(requestedGeneration, requestedUri)
                        || !applyView(reconciled)) {
                    return;
                }
                autoAttempts = 0;
                schedule(reconciled, false);
                LyricsSessionSeatTransition.Action action = LyricsSessionSeatTransition.reconcile(
                        document != null, reconciled.document != null, pendingPlan != null);
                if (action == LyricsSessionSeatTransition.Action.PUBLISH_SEAT) {
                    publishSeat(requestedTrack, requestedUri, requestedGeneration,
                            reconciled.document, reconciled.sourceRevision);
                } else if (action == LyricsSessionSeatTransition.Action.RETIRE_LOADING
                        || action == LyricsSessionSeatTransition.Action.RETIRE_NO_LYRICS) {
                    retireDisplayedBase(action == LyricsSessionSeatTransition.Action.RETIRE_LOADING,
                            requestedGeneration, settledReason(reconciled.plan));
                }
                maybeFetch();
            });
        });
    }

    /** Withdraws an ineligible Auto base while keeping the current track and generation alive. */
    private void retireDisplayedBase(boolean acquisitionPlanned, int requestedGeneration,
                                     String settledReason) {
        document = null;
        canonicalSource = null;
        session = null;
        displayedCandidateId = "";
        detectionArtifact = null;
        awaitingDetection = false;
        secondaryProcessing.cancelActive();
        detectionSession.cancelActive();
        status = acquisitionPlanned ? "loading" : "no_lyrics";
        noLyricsReason = acquisitionPlanned ? ""
                : settledReason == null ? "Lyrics unavailable" : settledReason;
        Snapshot retired = snapshot();
        notifyDocument(retired, null);
        if (!acquisitionPlanned) {
            for (RequestRecord request : takeRequests(requestedGeneration)) {
                request.callback.onError(noLyricsReason);
            }
        }
        notifyState(retired);
    }

    private void completeCatalogAction(LyricsHost.CatalogActionCallback callback,
                                       boolean success, String detail) {
        if (callback == null) return;
        handler.post(() -> {
            try {
                callback.onComplete(success, detail == null ? "" : detail);
            } catch (Throwable error) {
                Diagnostics.warn("LyricsSessionManager", "catalogActionCallback", error);
            }
        });
    }

    /**
     * An automatic fetch ended without a delivery. Adapters already committed each source's
     * outcome, so the next attempt (if any) is planned from stored state. A failed optional
     * refresh never replaces a rendered base with an error state.
     */
    private void acceptError(SpotifyTrack requestedTrack, String requestedUri,
                             int requestedGeneration, String error) {
        if (!policy.accepts(requestedGeneration, requestedUri)) return;
        if (requestedUri.equals(loadingUri)) loadingUri = "";
        planAfterAttempt(requestedTrack, requestedUri, requestedGeneration);
        if (!LyricsSessionSeatTransition.fetchFailureNeedsNoLyrics(document != null)) return;
        reportNoLyrics(requestedGeneration, error);
    }

    private void planAfterAttempt(SpotifyTrack requestedTrack, String requestedUri,
                                  int requestedGeneration) {
        NativeRuntime.LYRICS_IO.execute(() -> {
            LyricsCatalog.View view = null;
            try {
                view = LyricsCatalog.current(context, requestedTrack, false);
            } catch (Throwable ignored) {
            }
            final LyricsCatalog.View planned = view;
            handler.post(() -> {
                if (!policy.accepts(requestedGeneration, requestedUri)) return;
                // Another attempt may already be armed or running; never double-book one.
                if (pendingPlan != null || requestedUri.equals(loadingUri)) return;
                schedule(planned, true);
            });
        });
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
        startSharedProcessing(track, document, policy.generation(),
                java.util.Collections.emptySet(), java.util.EnumSet.of(layer));
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
                java.util.EnumSet.of(layer), java.util.EnumSet.of(layer)).contains(layer)
                ? AiRequestStartResult.STARTED : AiRequestStartResult.NOTHING_TO_DO;
    }

    /** Drops the accepted AI overlay and republishes the canonical baseline only. */
    void restoreLayer(LayerKind layer) {
        if (layer == null || document == null || session == null) return;
        // Layer-scoped: the sibling lane keeps its run, so it still completes (or stays
        // explicitly retryable) instead of stranding in PROCESSING with its work cancelled.
        secondaryProcessing.cancelLayer(layer);
        LayerState restored = LayerState.absent(layer);
        if (layer == LayerKind.MEANING
                && session.meaning.artifact instanceof MeaningArtifact) {
            MeaningArtifact baseline = ((MeaningArtifact) session.meaning.artifact).googleBaseline();
            if (baseline != null) {
                restored = restored.withArtifact(LayerStatus.READY, baseline, "");
            }
        }
        session = session.withLayer(layer, restored);
        // Keep the processing input identity: the sibling lane still addresses this document.
        // notifyDocument composes the restored layer from session state for every subscriber.
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
        switch (kind) {
            case TRANSLATION:
                secondaryProcessing.cancelLayer(LayerKind.MEANING);
                LyricCaches.clearGoogle(context);
                LyricCaches.clearMeaningArtifacts(context);
                AIPaidArtifactCache.clearLayer(context, LayerKind.MEANING);
                refreshLayer(LayerKind.MEANING);
                break;
            case TRANSLITERATION:
                secondaryProcessing.cancelLayer(LayerKind.SOUND);
                LyricCaches.clearSoundArtifacts(context);
                AIPaidArtifactCache.clearLayer(context, LayerKind.SOUND);
                refreshLayer(LayerKind.SOUND);
                break;
            case AI:
                secondaryProcessing.cancelActive();
                AIPaidArtifactCache.clear(context);
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
                secondaryProcessing.cancelActive();
                // Transport cache only: saved song data in the catalog is never a cache.
                String currentUri = policy.trackUri();
                if (!currentUri.isEmpty()) {
                    LyricsResponseCache.remove(context,
                            com.eza.spicyex.lyrics.LyricUtils.trackIdFromUri(currentUri));
                }
                reloadCurrentSource();
                break;
        }
    }

    /**
     * Owner-requested reload: every automatic source is asked again regardless of stored
     * outcomes. The rendered seat stays until a commit elects a different one.
     */
    private void reloadCurrentSource() {
        if (track == null || policy.trackUri().isEmpty()) return;
        fetchCoordinator.invalidate(track);
        loadingUri = "";
        // An explicit reload bypasses the error backoff: the owner just asked for this track now.
        nextFetchAtMs = 0L;
        autoAttempts = 0;
        pendingPlan = AcquisitionPlanner.refreshAll(CatalogPolicy.read(context));
        if (document == null) {
            status = "loading";
            notifyState(snapshot());
        }
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
        return startSharedProcessing(requestedTrack, snapshot, requestedGeneration,
                explicitAiRequests, java.util.EnumSet.allOf(LayerKind.class));
    }

    private java.util.Set<LayerKind> startSharedProcessing(
            SpotifyTrack requestedTrack, LyricsDocument snapshot, int requestedGeneration,
            java.util.Set<LayerKind> explicitAiRequests,
            java.util.Set<LayerKind> requestedLayers) {
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
                }, requestedLayers);
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
                snapshotClock.getAsLong());
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
