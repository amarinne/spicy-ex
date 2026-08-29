package com.eza.spicyex.lyrics;

import android.content.Context;
import android.os.Handler;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.eza.spicyex.Diagnostics;
import com.eza.spicyex.lyrics.ai.AiCancelledException;
import com.eza.spicyex.lyrics.ai.AiMeaningRun;
import com.eza.spicyex.lyrics.ai.AiLiveMonitor;
import com.eza.spicyex.lyrics.ai.AiRunOutcome;
import com.eza.spicyex.lyrics.ai.AiRunMonitor;
import com.eza.spicyex.lyrics.ai.AiRequestLiveState;
import com.eza.spicyex.lyrics.ai.AiRuntimeFailureLog;
import com.eza.spicyex.lyrics.ai.AiSettings;
import com.eza.spicyex.lyrics.ai.AiSignal;
import com.eza.spicyex.lyrics.ai.AiText;
import com.eza.spicyex.lyrics.session.CanonicalBase;
import com.eza.spicyex.lyrics.session.CanonicalRow;
import com.eza.spicyex.lyrics.session.LayerAuthority;
import com.eza.spicyex.lyrics.session.LayerFailure;
import com.eza.spicyex.lyrics.session.LayerKind;
import com.eza.spicyex.lyrics.session.LayerProvenance;
import com.eza.spicyex.lyrics.session.MeaningArtifact;
import com.eza.spicyex.lyrics.session.MeaningEntry;
import com.eza.spicyex.lyrics.session.LayerRunCoalescer;
import com.eza.spicyex.lyrics.session.LayerRunIdentity;
import com.eza.spicyex.lyrics.session.LyricPipelineMetrics;

import de.robv.android.xposed.XposedBridge;
import okhttp3.OkHttpClient;
import static com.eza.spicyex.lyrics.LyricUtils.isBlank;
import static com.eza.spicyex.lyrics.LyricUtils.safe;

/**
 * The Meaning lane: machine translation behind a backend contract.
 *
 * <p>Owns its own executor, run bookkeeping, cache identity, and completion. Original lyrics and
 * the Sound layer never wait for it, and a failure here leaves both untouched and usable.
 */
public final class LyricsMeaningLane {
    private static final String TAG = "[SpotifyPlusMeaningLane]";
    /** Diagnostic-capture component for the AI side of this lane. */
    private static final String AI_COMPONENT = "ai_meaning";
    private static final int TRANSLATION_BATCH_MAX_LINES = 100;
    private static final int TRANSLATION_BATCH_MAX_CHARS = 4500;
    private static final int TRANSLATION_RETRY_MAX_LINES = 12;
    private static final int TRANSLATION_RETRY_MAX_CHARS = 1200;
    private static final int TRANSLATION_SINGLE_RESCUE_MAX_ROWS = 8;

    /**
     * A machine Meaning backend. Google is one peer behind this contract; nothing above the lane
     * knows which backend answered.
     */
    public interface MeaningProvider {
        /** Stable backend identity for provenance and cache config. */
        String backendId();

        boolean handles(String backendSetting);

        /** @param cancelTag identifies this run's calls so a retired run can cancel them */
        GoogleEnhancer.BatchResult translate(Context context, OkHttpClient http, int processingVersion,
                                             String trackId, String sourceLang, String targetLang,
                                             List<GoogleEnhancer.BatchLine> batch, String cancelTag);

        /** Aborts every in-flight call this backend started under {@code cancelTag}. */
        void cancel(OkHttpClient http, String cancelTag);

        boolean shouldDisplay(String sourceText, String translated);
    }

    /** The unofficial Google endpoint, kept behind the provider contract. */
    public static final class GoogleMeaningProvider implements MeaningProvider {
        @Override public String backendId() {
            return "google_unofficial";
        }

        @Override public boolean handles(String backendSetting) {
            return "google_unofficial".equalsIgnoreCase(backendSetting);
        }

        @Override public GoogleEnhancer.BatchResult translate(Context context, OkHttpClient http,
                                                              int processingVersion, String trackId,
                                                              String sourceLang, String targetLang,
                                                              List<GoogleEnhancer.BatchLine> batch,
                                                              String cancelTag) {
            return GoogleEnhancer.translateBatch(context, http, processingVersion, trackId,
                    sourceLang, targetLang, batch, cancelTag);
        }

        @Override public void cancel(OkHttpClient http, String cancelTag) {
            GoogleEnhancer.cancelTagged(http, cancelTag);
        }

        @Override public boolean shouldDisplay(String sourceText, String translated) {
            return GoogleEnhancer.shouldDisplayTranslation(sourceText, translated);
        }
    }

    /**
     * Process-wide, because fullscreen, now-playing, and HyperGlow each hold their own lane
     * instance. Identical Meaning work must cost one provider run no matter how many surfaces ask.
     */
    private static final LayerRunCoalescer COALESCER = new LayerRunCoalescer();

    private final Context context;
    private final OkHttpClient http;
    private final ExecutorService laneExecutor;
    /** AI generation runs here so a 60s call never occupies a translation worker. */
    private final ExecutorService aiExecutor;
    private final Handler handler;
    private final int processingVersion;
    private final MeaningProvider provider;
    /** Retires earlier runs of this lane: only the newest sequence may publish. */
    private final AtomicLong laneSequence = new AtomicLong();
    /** Call tag of the run currently allowed to publish; empty when the lane is idle. */
    private volatile String activeTag = "";
    /** Cancels the AI run in flight, if this lane started one. */
    private volatile AiSignal aiSignal;

    public LyricsMeaningLane(Context context, OkHttpClient http, ExecutorService laneExecutor,
                             ExecutorService aiExecutor, Handler handler, int processingVersion) {
        this(context, http, laneExecutor, aiExecutor, handler, processingVersion,
                new GoogleMeaningProvider());
    }

    public LyricsMeaningLane(Context context, OkHttpClient http, ExecutorService laneExecutor,
                             ExecutorService aiExecutor, Handler handler, int processingVersion,
                             MeaningProvider provider) {
        this.context = context;
        this.http = http;
        this.laneExecutor = laneExecutor;
        this.aiExecutor = aiExecutor == null ? laneExecutor : aiExecutor;
        this.handler = handler;
        this.processingVersion = processingVersion;
        this.provider = provider;
    }

    /** @return true when a run was started; false when the layer is disabled or already satisfied */
    public boolean start(
            String id,
            int generation,
            LyricsDocument snapshot,
            String backend,
            String targetLang,
            String sourceLang,
            String effectiveSourceLang,
            boolean explicitAiRequest,
            LyricsSecondaryProcessor.CurrentGuard currentGuard,
            LyricsSecondaryProcessor.Callback callback
    ) {
        if (snapshot == null || snapshot.lines.isEmpty()) return false;
        final AiSettings aiSettings = new AiSettings(context);
        final boolean aiAutomatic = aiSettings.translationAutomatic() && aiSettings.canRequest();
        final boolean wanted = snapshot.translationPending && provider.handles(backend);
        LyricsDocument workerSnapshot = LyricsDocument.copyOf(snapshot);
        if (workerSnapshot == null || workerSnapshot.lines.isEmpty()) return false;

        List<Integer> work = new ArrayList<>();
        if (wanted) {
            for (int i = 0; i < workerSnapshot.lines.size(); i++) {
                LyricsLine line = workerSnapshot.lines.get(i);
                if (line == null || isBlank(line.text) || line.interlude) continue;
                if (isBlank(line.translatedText)
                        && SpicyProcessing.flagsFor(line.text, sourceLang, targetLang).translationPending) {
                    work.add(i);
                }
            }
        }

        final DerivedLayerRun run = DerivedLayerRun.begin(context, LayerKind.MEANING, workerSnapshot, laneSequence);
        String retired = activeTag;
        activeTag = run.tag;
        if (!retired.isEmpty()) provider.cancel(http, retired);

        // AI remains the display authority. The composer may optionally ask for a Google draft as
        // request input; otherwise Meaning reads canonical source directly. Google preview is the
        // third flow: raw-lyrics AI semantics plus an independent Google display job.
        final AiSettings.MeaningFlow meaningFlow = aiSettings.meaningFlow();
        boolean aiConfigured = aiSettings.meaningLayerEnabled() && aiSettings.isEnabled()
                && !aiSettings.modelName().isEmpty();
        boolean aiAutomaticRequest = aiAutomatic && snapshot.translationPending;
        if (aiConfigured && (wanted || explicitAiRequest || aiAutomatic
                || !snapshot.translationPending)) {
            if (meaningFlow == AiSettings.MeaningFlow.GOOGLE_PREVIEW) {
                return startPreviewRun(run, id, generation, snapshot, workerSnapshot, work,
                        backend, targetLang, sourceLang, effectiveSourceLang, explicitAiRequest,
                        explicitAiRequest || aiAutomaticRequest, currentGuard, callback);
            }
            return startAiRun(run, id, generation, snapshot, workerSnapshot, work, backend,
                    targetLang, sourceLang, effectiveSourceLang, explicitAiRequest,
                    explicitAiRequest || aiAutomaticRequest, currentGuard, callback);
        }

        if (work.isEmpty()) {
            post(run, id, generation, snapshot, currentGuard,
                    () -> callback.complete(LayerKind.MEANING, null, LayerFailure.NONE, "", 0));
            return false;
        }

        // One provider run per (canonical base, Meaning config), whichever surface asks first. A
        // second surface waits for the owner and then fills from cache instead of re-billing.
        LayerRunIdentity runIdentity = new LayerRunIdentity(safe(id), generation, 1,
                run.canonicalDigest(), LayerKind.MEANING, run.configId(), "", run.tag);
        if (!COALESCER.beginOrDefer(runIdentity, () -> {
            LyricPipelineMetrics.increment(LyricPipelineMetrics.Counter.COALESCED_RUN_JOINED);
            start(id, generation, snapshot, backend, targetLang, sourceLang, effectiveSourceLang,
                    explicitAiRequest, currentGuard, callback);
        })) {
            return false;
        }

        final long startedAtMs = SystemClock.elapsedRealtime();
        laneExecutor.execute(() -> {
            AtomicInteger changed = new AtomicInteger();
            Set<Integer> translated = new HashSet<>();
            GoogleRunStats googleStats = new GoogleRunStats(work == null ? 0 : work.size());
            // Collected as the lane works rather than read back off the document. Provider-supplied
            // translations are deliberately absent: they are canonical source data carried by the
            // base, not something this layer produced.
            List<MeaningEntry> entries = new ArrayList<>();
            try {
                if (!run.accepts(currentGuard, id, generation, snapshot)) return;
                List<Integer> retry = translateBatchPass(id, effectiveSourceLang, targetLang,
                        workerSnapshot, work, entries, translated, changed, true, run.tag, run,
                        googleStats);
                if (!retry.isEmpty() && run.accepts(currentGuard, id, generation, snapshot)) {
                    post(run, id, generation, snapshot, currentGuard,
                            () -> callback.progress("Retrying echoed translations... " + retry.size()));
                    translateBatchPass(id, effectiveSourceLang, targetLang, workerSnapshot, retry,
                            entries, translated, changed, false, run.tag, run, googleStats);
                }
                List<Integer> rescue = untranslated(work, translated);
                if (!rescue.isEmpty() && rescue.size() <= TRANSLATION_SINGLE_RESCUE_MAX_ROWS
                        && run.accepts(currentGuard, id, generation, snapshot)) {
                    translateBatchPass(id, effectiveSourceLang, targetLang, workerSnapshot, rescue,
                            entries, translated, changed, false, run.tag, run, googleStats,
                            1, 512);
                }
            } catch (Throwable t) {
                XposedBridge.log(TAG + " translation pass failed: " + t.getClass().getSimpleName());
            } finally {
                // Release before publishing so a deferred surface re-runs against the fresh cache.
                COALESCER.finish(runIdentity);
            }

            boolean complete = translated.containsAll(work);
            logGoogleSettlement(googleStats, translated.size(), complete,
                    SystemClock.elapsedRealtime() - startedAtMs);
            boolean includes = complete
                    && (LyricsDocumentProcessor.hasDisplayedTranslation(workerSnapshot) || !translated.isEmpty());
            int finalChanged = changed.get();
            LyricPipelineMetrics.increment(LyricPipelineMetrics.Counter.MEANING_PROCESSED);
            LyricPipelineMetrics.record(LyricPipelineMetrics.Timing.MEANING_PROCESSING,
                    SystemClock.elapsedRealtime() - startedAtMs);
            post(run, id, generation, snapshot, currentGuard,
                    () -> callback.complete(LayerKind.MEANING, artifactOf(run, entries, complete),
                            LayerFailure.NONE,
                            "Enhanced " + finalChanged + " translation fields", finalChanged));
        });
        return true;
    }

    /**
     * The AI path for this layer.
     *
     * <p>Shaped like the Google path deliberately — same run guard, same coalescer, same completion
     * callback — so nothing above the lane has to know which authority answered. What differs is
     * underneath: one request for the whole document rather than provider-tuned batches, and a
     * cancellation that reaches the socket rather than only the callback.
     */
    private boolean startAiRun(final DerivedLayerRun run, final String id, final int generation,
                               final LyricsDocument snapshot, final LyricsDocument workerSnapshot,
                               final List<Integer> googleWork, final String backend,
                               final String targetLang, final String sourceLang,
                               final String effectiveSourceLang,
                               final boolean explicitAiRequest,
                               final boolean allowProviderRequest,
                               final LyricsSecondaryProcessor.CurrentGuard currentGuard,
                               final LyricsSecondaryProcessor.Callback callback) {
        final LayerRunIdentity runIdentity = new LayerRunIdentity(safe(id), generation, 1,
                run.canonicalDigest(), LayerKind.MEANING, run.configId(), "", run.tag);
        if (!COALESCER.beginOrDefer(runIdentity, new Runnable() {
            @Override public void run() {
                LyricPipelineMetrics.increment(LyricPipelineMetrics.Counter.COALESCED_RUN_JOINED);
                // Re-enter rather than drop, exactly as the Google path does. By the time this
                // runs the owner has written its record, so the replay settles from cache without
                // a second billable call — and the request that was deferred is not lost.
                start(id, generation, snapshot, backend, targetLang, sourceLang,
                        effectiveSourceLang, explicitAiRequest, currentGuard, callback);
            }
        })) {
            // Another surface — or the automatic run for this same song — already owns this exact
            // work. An explicit request still counts as started: it is queued above and will
            // settle when the owner finishes. Reporting it as "not started" is what told the user
            // their configuration was broken when it was not.
            return explicitAiRequest;
        }

        final AiSignal signal = new AiSignal();
        aiSignal = signal;
        if (allowProviderRequest) {
            AiRequestLiveState.begin(LayerKind.MEANING, run.canonicalDigest(), run.tag);
            Diagnostics.event(AI_COMPONENT, "request_started",
                    Diagnostics.context("provider", aiProviderId()));
        }
        final AiRunMonitor monitor = allowProviderRequest
                ? new AiLiveMonitor(LayerKind.MEANING, run.canonicalDigest(), run.tag)
                : null;
        final long startedAtMs = SystemClock.elapsedRealtime();
        // The coalescer key is claimed above, on this thread. If the executor refuses the task
        // nothing would ever release it, and every later request for this song would be told the
        // work is already in flight — forever, with no owner.
        try {
            aiExecutor.execute(new Runnable() {
            @Override public void run() {
                AiMeaningRun.Result result = null;
                LayerFailure aiFailure = LayerFailure.NONE;
                MeaningArtifact googleBaseline = null;
                boolean refineGoogle = false;
                try {
                    if (!run.accepts(currentGuard, id, generation, snapshot)) return;
                    AiSettings settings = new AiSettings(context);
                    // Only Google draft refines. AI-only runs raw by contract; the preview flow
                    // never enters this path.
                    refineGoogle = settings.meaningFlow() == AiSettings.MeaningFlow.GOOGLE_DRAFT;
                    if (refineGoogle) {
                        GoogleFallbackResult googleResult = googleFallback(run, id, workerSnapshot,
                                googleWork, effectiveSourceLang, targetLang);
                        googleBaseline = googleResult.artifact;
                        postGoogleFailureIfNeeded(run, id, generation, snapshot, currentGuard,
                                callback, googleResult.failure);
                    }
                    if (googleBaseline != null) {
                        final MeaningArtifact preliminary = googleBaseline;
                        post(run, id, generation, snapshot, currentGuard, new Runnable() {
                            @Override public void run() {
                                callback.rerender(LayerKind.MEANING, preliminary,
                                        "Showing Google translation while AI works");
                            }
                        });
                    }
                    result = AiMeaningRun.run(context, settings, run.base,
                            workerSnapshot, googleBaseline, refineGoogle, targetLang,
                            allowProviderRequest, signal, monitor);
                    if (result != null && result.outcome != null
                            && result.outcome.kind == AiRunOutcome.Kind.FAILED) {
                        aiFailure = result.outcome.failure;
                        AiRequestLiveState.fail(LayerKind.MEANING, run.canonicalDigest(), run.tag,
                                result.outcome.failureToken, result.outcome.failure.httpStatus,
                                result.outcome.failureDetail);
                        recordAiOutcome("request_failed", "failed",
                                result.outcome.failureToken,
                                result.outcome.failure.httpStatus);
                        XposedBridge.log(TAG + " ai translation outcome=failed token="
                                + result.outcome.failureToken + " status="
                                + result.outcome.failure.httpStatus + " rule="
                                + result.outcome.failureDetail);
                    } else if (result != null && result.outcome != null
                            && (result.outcome.kind == AiRunOutcome.Kind.COMPLETED
                            || result.outcome.kind == AiRunOutcome.Kind.REUSED)) {
                        recordAiOutcome("request_settled",
                                result.outcome.kind.name().toLowerCase(java.util.Locale.ROOT),
                                "", 0);
                        XposedBridge.log(TAG + " ai translation outcome="
                                + result.outcome.kind.name().toLowerCase(java.util.Locale.ROOT)
                                + " durable=" + result.outcome.durable);
                    }
                } catch (AiCancelledException cancelled) {
                    // Expected on a track change; the run already stored whatever it had finished.
                    AiRequestLiveState.cancel(LayerKind.MEANING, run.canonicalDigest(), run.tag);
                    recordAiOutcome("request_settled", "cancelled", "", 0);
                    return;
                } catch (Throwable failure) {
                    XposedBridge.log(TAG + " ai translation failed: "
                            + AiRuntimeFailureLog.describe(failure));
                    aiFailure = new LayerFailure(LayerFailure.Reason.UNAVAILABLE,
                            "runtime_unavailable", 0);
                    AiRequestLiveState.fail(LayerKind.MEANING, run.canonicalDigest(), run.tag,
                            "runtime_unavailable", 0, failure.getClass().getSimpleName());
                    recordAiOutcome("request_failed", "failed", "runtime_unavailable", 0);
                } finally {
                    COALESCER.finish(runIdentity);
                }
                AiRequestLiveState.complete(LayerKind.MEANING, run.canonicalDigest(), run.tag);

                MeaningArtifact aiArtifact = result == null ? null : result.artifact;
                if (aiArtifact != null && googleBaseline != null) {
                    aiArtifact = aiArtifact.withGoogleBaseline(googleBaseline, refineGoogle);
                }
                // Every AI Meaning mode uses the same document-level display selector. AI-only
                // has no Google candidate; Google-draft may fall back only to a complete baseline.
                if (googleBaseline == null && refineGoogle) {
                    GoogleFallbackResult googleResult = googleFallback(run, id, workerSnapshot,
                            googleWork, effectiveSourceLang, targetLang);
                    googleBaseline = googleResult.artifact;
                    postGoogleFailureIfNeeded(run, id, generation, snapshot, currentGuard,
                            callback, googleResult.failure);
                }
                Set<String> requiredRows = requiredRowIds(
                        run.base, workerSnapshot, sourceLang, targetLang);
                MeaningArtifact artifact = refineGoogle
                        ? MeaningDisplaySelector.selectWithFallback(
                                run.base, requiredRows, aiArtifact, googleBaseline)
                        : MeaningDisplaySelector.select(
                                run.base, requiredRows, aiArtifact, null);
                final MeaningArtifact finalArtifact = artifact;
                final LayerFailure finalFailure = aiFailure;
                final int changed = finalArtifact == null ? 0 : finalArtifact.size();
                LyricPipelineMetrics.increment(LyricPipelineMetrics.Counter.MEANING_PROCESSED);
                LyricPipelineMetrics.record(LyricPipelineMetrics.Timing.MEANING_PROCESSING,
                        SystemClock.elapsedRealtime() - startedAtMs);
                post(run, id, generation, snapshot, currentGuard, new Runnable() {
                    @Override public void run() {
                        callback.complete(LayerKind.MEANING, finalArtifact, finalFailure,
                                "AI translated " + changed + " lines", changed);
                    }
                });
            }
            });
        } catch (RuntimeException notDispatched) {
            COALESCER.finish(runIdentity);
            AiRequestLiveState.cancel(LayerKind.MEANING, run.canonicalDigest(), run.tag);
            XposedBridge.log(TAG + " ai translation not dispatched: "
                    + notDispatched.getClass().getSimpleName());
            return false;
        }
        return true;
    }

    /**
     * The Google preview flow: two independent children under one parent run contract.
     *
     * <p>Google is display-only work on the lane executor, so it can never queue behind an AI
     * request. The AI request carries canonical raw lyrics only — {@code googleBaseline=null},
     * {@code refineGoogle=false} — which makes it share the plain Meaning paid-record identity
     * and prompt version with AI-only mode. {@link MeaningPreviewRace} owns which settlement may
     * reach the screen; this method owns exactly-once coalescer release, at-most-one preliminary
     * rerender, and the single completion.
     *
     * <p>The selected preview flow always resolves Google first. Google owns its own line cache,
     * so a repeat visit can reuse that result without letting a paid AI artifact bypass the
     * requested display flow.
     */
    private boolean startPreviewRun(final DerivedLayerRun run, final String id, final int generation,
                                    final LyricsDocument snapshot, final LyricsDocument workerSnapshot,
                                    final List<Integer> googleWork, final String backend,
                                    final String targetLang, final String sourceLang,
                                    final String effectiveSourceLang,
                                    final boolean explicitAiRequest,
                                    final boolean allowProviderRequest,
                                    final LyricsSecondaryProcessor.CurrentGuard currentGuard,
                                    final LyricsSecondaryProcessor.Callback callback) {
        final LayerRunIdentity runIdentity = new LayerRunIdentity(safe(id), generation, 1,
                run.canonicalDigest(), LayerKind.MEANING, run.configId(), "", run.tag);
        if (!COALESCER.beginOrDefer(runIdentity, new Runnable() {
            @Override public void run() {
                LyricPipelineMetrics.increment(LyricPipelineMetrics.Counter.COALESCED_RUN_JOINED);
                // Same re-entry contract as the other flows: the deferred request replays once
                // the owner has written its record and settles from cache instead of re-billing.
                start(id, generation, snapshot, backend, targetLang, sourceLang,
                        effectiveSourceLang, explicitAiRequest, currentGuard, callback);
            }
        })) {
            return explicitAiRequest;
        }

        final MeaningPreviewRace race = new MeaningPreviewRace(
                run.base, requiredRowIds(run.base, workerSnapshot, sourceLang, targetLang));
        final AiSignal signal = new AiSignal();
        aiSignal = signal;
        if (allowProviderRequest) {
            AiRequestLiveState.begin(LayerKind.MEANING, run.canonicalDigest(), run.tag);
            Diagnostics.event(AI_COMPONENT, "request_started",
                    Diagnostics.context("provider", aiProviderId()));
        }
        final AiRunMonitor monitor = allowProviderRequest
                ? new AiLiveMonitor(LayerKind.MEANING, run.canonicalDigest(), run.tag)
                : null;
        final long startedAtMs = SystemClock.elapsedRealtime();
        // Preview means Google reaches the display before a new or cached AI answer can replace it.
        final CountDownLatch previewReadyForAi = new CountDownLatch(1);

        // Google display child. Lane executor, immediately; it shares the parent's run guard and
        // cancellation tag but never the AI executor's queue.
        try {
            laneExecutor.execute(new Runnable() {
                @Override public void run() {
                        MeaningArtifact google = null;
                        LayerFailure googleFailure = LayerFailure.NONE;
                    try {
                        if (!run.accepts(currentGuard, id, generation, snapshot)) {
                            race.abandonAi();
                            previewReadyForAi.countDown();
                            AiRequestLiveState.cancel(LayerKind.MEANING, run.canonicalDigest(),
                                    run.tag);
                            COALESCER.finish(runIdentity);
                            return;
                        }
                        GoogleFallbackResult googleResult = googleFallback(run, id, workerSnapshot,
                                googleWork, effectiveSourceLang, targetLang);
                        google = googleResult.artifact;
                        googleFailure = googleResult.failure;
                    } catch (Throwable failure) {
                        XposedBridge.log(TAG + " preview google failed: "
                                + failure.getClass().getSimpleName());
                    }
                    final MeaningPreviewRace.Outcome googleOutcome = race.onGoogleSettled(google);
                    if (googleFailure.isFailure()) {
                        postGoogleFailureIfNeeded(run, id, generation, snapshot, currentGuard,
                                callback, googleFailure);
                    }
                    if (googleOutcome.preliminary) {
                        final MeaningArtifact preliminary = googleOutcome.artifact;
                        handler.post(new Runnable() {
                            @Override public void run() {
                                try {
                                    if (run.accepts(currentGuard, id, generation, snapshot)
                                            && race.mayPublishPreliminary(preliminary)) {
                                        callback.rerender(LayerKind.MEANING, preliminary,
                                                "Showing Google translation while AI works");
                                    }
                                } finally {
                                    previewReadyForAi.countDown();
                                }
                            }
                        });
                    } else {
                        previewReadyForAi.countDown();
                        completePreviewRun(run, id, generation, snapshot, currentGuard, callback,
                                runIdentity, googleOutcome);
                    }
                }
            });
        } catch (RuntimeException notDispatched) {
            previewReadyForAi.countDown();
            // No Google child will settle: the race falls through to whatever the raw-AI child
            // decides, exactly as if Google had failed.
            MeaningPreviewRace.Outcome googleOutcome = race.onGoogleSettled(null);
            completePreviewRun(run, id, generation, snapshot, currentGuard, callback,
                    runIdentity, googleOutcome);
            XposedBridge.log(TAG + " preview google not dispatched: "
                    + notDispatched.getClass().getSimpleName());
        }

        // The coalescer key is claimed above, on this thread. The child that produces the race's
        // terminal outcome releases it; cancellation and dispatch rejection release explicitly.
        try {
            aiExecutor.execute(new Runnable() {
                @Override public void run() {
                    AiMeaningRun.Result result = null;
                    LayerFailure aiFailure = LayerFailure.NONE;
                    try {
                        try {
                            previewReadyForAi.await();
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            race.abandonAi();
                            AiRequestLiveState.cancel(LayerKind.MEANING, run.canonicalDigest(),
                                    run.tag);
                            COALESCER.finish(runIdentity);
                            return;
                        }
                        if (!run.accepts(currentGuard, id, generation, snapshot)) {
                            race.abandonAi();
                            AiRequestLiveState.cancel(LayerKind.MEANING, run.canonicalDigest(),
                                    run.tag);
                            COALESCER.finish(runIdentity);
                            return;
                        }
                        // Raw lyrics only. Preview never sends a baseline and never reports
                        // baseline_unavailable: Google here is display, not request input.
                        result = AiMeaningRun.run(context, new AiSettings(context), run.base,
                                workerSnapshot, null, false, targetLang,
                                allowProviderRequest, signal, monitor);
                        if (result != null && result.outcome != null
                                && result.outcome.kind == AiRunOutcome.Kind.FAILED) {
                            aiFailure = result.outcome.failure;
                            AiRequestLiveState.fail(LayerKind.MEANING, run.canonicalDigest(),
                                    run.tag, result.outcome.failureToken,
                                    result.outcome.failure.httpStatus,
                                    result.outcome.failureDetail);
                            recordAiOutcome("request_failed", "failed",
                                    result.outcome.failureToken,
                                    result.outcome.failure.httpStatus);
                            XposedBridge.log(TAG + " ai translation outcome=failed token="
                                    + result.outcome.failureToken + " status="
                                    + result.outcome.failure.httpStatus + " rule="
                                    + result.outcome.failureDetail);
                        } else if (result != null && result.outcome != null
                                && (result.outcome.kind == AiRunOutcome.Kind.COMPLETED
                                || result.outcome.kind == AiRunOutcome.Kind.REUSED)) {
                            recordAiOutcome("request_settled",
                                    result.outcome.kind.name().toLowerCase(java.util.Locale.ROOT),
                                    "", 0);
                            XposedBridge.log(TAG + " ai translation outcome="
                                    + result.outcome.kind.name().toLowerCase(java.util.Locale.ROOT)
                                    + " durable=" + result.outcome.durable);
                        }
                    } catch (AiCancelledException cancelled) {
                        // Expected on a track change; the run already stored whatever it had.
                        AiRequestLiveState.cancel(LayerKind.MEANING, run.canonicalDigest(),
                                run.tag);
                        recordAiOutcome("request_settled", "cancelled", "", 0);
                        COALESCER.finish(runIdentity);
                        return;
                    } catch (Throwable failure) {
                        XposedBridge.log(TAG + " ai translation failed: "
                                + AiRuntimeFailureLog.describe(failure));
                        aiFailure = new LayerFailure(LayerFailure.Reason.UNAVAILABLE,
                                "runtime_unavailable", 0);
                        AiRequestLiveState.fail(LayerKind.MEANING, run.canonicalDigest(), run.tag,
                                "runtime_unavailable", 0, failure.getClass().getSimpleName());
                        recordAiOutcome("request_failed", "failed", "runtime_unavailable", 0);
                    }
                    AiRequestLiveState.complete(LayerKind.MEANING, run.canonicalDigest(), run.tag);

                    MeaningArtifact artifact = result == null ? null : result.artifact;
                    // The preliminary was display-only: the paid record stays keyed to raw
                    // lyrics, so no Google baseline is folded into the final artifact.
                    final MeaningPreviewRace.Outcome outcome = race.onAiSettled(artifact,
                            aiFailure);
                    LyricPipelineMetrics.increment(LyricPipelineMetrics.Counter.MEANING_PROCESSED);
                    LyricPipelineMetrics.record(LyricPipelineMetrics.Timing.MEANING_PROCESSING,
                            SystemClock.elapsedRealtime() - startedAtMs);
                    completePreviewRun(run, id, generation, snapshot, currentGuard, callback,
                            runIdentity, outcome);
                }
            });
        } catch (RuntimeException notDispatched) {
            race.abandonAi();
            COALESCER.finish(runIdentity);
            AiRequestLiveState.cancel(LayerKind.MEANING, run.canonicalDigest(), run.tag);
            // The same tag owns both children's calls, so this aborts the Google child too.
            provider.cancel(http, run.tag);
            XposedBridge.log(TAG + " preview ai translation not dispatched: "
                    + notDispatched.getClass().getSimpleName());
            return false;
        }
        return true;
    }

    /** Publishes the preview race's single terminal outcome; non-terminal settlements are no-ops. */
    private void completePreviewRun(DerivedLayerRun run, String id, int generation,
                                    LyricsDocument snapshot,
                                    LyricsSecondaryProcessor.CurrentGuard currentGuard,
                                    LyricsSecondaryProcessor.Callback callback,
                                    LayerRunIdentity runIdentity,
                                    MeaningPreviewRace.Outcome outcome) {
        if (outcome == null || !outcome.terminal) return;
        COALESCER.finish(runIdentity);
        final int changed = outcome.artifact == null ? 0 : outcome.artifact.size();
        post(run, id, generation, snapshot, currentGuard, new Runnable() {
            @Override public void run() {
                callback.complete(LayerKind.MEANING, outcome.artifact, outcome.failure,
                        "AI translated " + changed + " lines", changed);
            }
        });
    }

    /**
     * One allowlisted diagnostic event for the AI side of this lane.
     *
     * <p>Only context keys already on the capture filter are used — {@code provider}, {@code
     * result}, {@code reason}, and {@code status} — so nothing here can silently drop. Tokens name
     * the failure; no lyric text, payload, or URL travels with them.
     */
    private void recordAiOutcome(String operation, String result, String reason, int httpStatus) {
        Diagnostics.event(AI_COMPONENT, operation, Diagnostics.context(
                "provider", aiProviderId(),
                "result", result,
                "reason", AiText.nz(reason),
                "status", httpStatus > 0 ? String.valueOf(httpStatus) : ""));
    }

    private String aiProviderId() {
        return new AiSettings(context).providerId();
    }

    private GoogleFallbackResult googleFallback(DerivedLayerRun run, String id,
                                           LyricsDocument workerSnapshot, List<Integer> work,
                                           String effectiveSourceLang, String targetLang) {
        List<MeaningEntry> entries = new ArrayList<>();
        for (int i = 0; workerSnapshot != null && i < workerSnapshot.lines.size(); i++) {
            LyricsLine line = workerSnapshot.lines.get(i);
            if (line == null || isBlank(line.translatedText)) continue;
            CanonicalRow row = run.base.rowAt(i);
            if (row != null && GoogleEnhancer.shouldDisplayTranslation(line.text,
                    line.translatedText)) {
                entries.add(new MeaningEntry(row.rowId, line.translatedText, targetLang));
            }
        }

        List<Integer> missing = work == null ? Collections.<Integer>emptyList()
                : new ArrayList<>(work);
        Set<Integer> translated = new HashSet<>();
        AtomicInteger changed = new AtomicInteger();
        GoogleRunStats stats = new GoogleRunStats(missing.size());
        if (!missing.isEmpty()) {
            try {
                List<Integer> retry = translateBatchPass(id, effectiveSourceLang, targetLang,
                        workerSnapshot, missing, entries, translated, changed, true, run.tag, run,
                        stats);
                if (!retry.isEmpty() && run.isNewest()) {
                    translateBatchPass(id, effectiveSourceLang, targetLang, workerSnapshot, retry,
                            entries, translated, changed, false, run.tag, run, stats);
                }
                List<Integer> rescue = untranslated(missing, translated);
                if (!rescue.isEmpty() && rescue.size() <= TRANSLATION_SINGLE_RESCUE_MAX_ROWS
                        && run.isNewest()) {
                    translateBatchPass(id, effectiveSourceLang, targetLang, workerSnapshot, rescue,
                            entries, translated, changed, false, run.tag, run, stats, 1, 512);
                }
            } catch (Throwable failure) {
                XposedBridge.log(TAG + " google fallback failed: "
                        + failure.getClass().getSimpleName());
            }
        }
        boolean complete = missing.isEmpty() || translated.containsAll(missing);
        logGoogleSettlement(stats, translated.size(), complete,
                SystemClock.elapsedRealtime() - stats.startedAtMs);
        MeaningArtifact artifact = entries.isEmpty() ? null : new MeaningArtifact(
                run.canonicalDigest(), run.configId(),
                new LayerProvenance(LayerAuthority.MACHINE, "google_unofficial", run.configId(),
                        System.currentTimeMillis()), entries, !complete);
        LayerFailure failure = stats.lastStatus == 429
                ? LayerFailure.http(429) : LayerFailure.NONE;
        return new GoogleFallbackResult(artifact, failure);
    }

    private void postGoogleFailureIfNeeded(DerivedLayerRun run, String id, int generation,
                                           LyricsDocument snapshot,
                                           LyricsSecondaryProcessor.CurrentGuard guard,
                                           LyricsSecondaryProcessor.Callback callback,
                                           LayerFailure failure) {
        if (failure == null || failure.reason != LayerFailure.Reason.RATE_LIMITED) return;
        post(run, id, generation, snapshot, guard,
                () -> callback.progress("Google translation rate limited"));
    }

    private static final class GoogleFallbackResult {
        final MeaningArtifact artifact;
        final LayerFailure failure;

        GoogleFallbackResult(MeaningArtifact artifact, LayerFailure failure) {
            this.artifact = artifact;
            this.failure = failure == null ? LayerFailure.NONE : failure;
        }
    }

    private List<Integer> translateBatchPass(
            String id, String sourceLang, String targetLang, LyricsDocument workerSnapshot,
            List<Integer> work, List<MeaningEntry> entries, Set<Integer> translatedIndices,
            AtomicInteger changed, boolean collectRetry, String cancelTag, DerivedLayerRun run
    ) {
        return translateBatchPass(id, sourceLang, targetLang, workerSnapshot, work, entries,
                translatedIndices, changed, collectRetry, cancelTag, run, null);
    }

    private List<Integer> translateBatchPass(
            String id, String sourceLang, String targetLang, LyricsDocument workerSnapshot,
            List<Integer> work, List<MeaningEntry> entries, Set<Integer> translatedIndices,
            AtomicInteger changed, boolean collectRetry, String cancelTag, DerivedLayerRun run,
            GoogleRunStats stats
    ) {
        int maxLines = collectRetry ? TRANSLATION_BATCH_MAX_LINES : TRANSLATION_RETRY_MAX_LINES;
        int maxChars = collectRetry ? TRANSLATION_BATCH_MAX_CHARS : TRANSLATION_RETRY_MAX_CHARS;
        return translateBatchPass(id, sourceLang, targetLang, workerSnapshot, work, entries,
                translatedIndices, changed, collectRetry, cancelTag, run, stats,
                maxLines, maxChars);
    }

    private List<Integer> translateBatchPass(
            String id, String sourceLang, String targetLang, LyricsDocument workerSnapshot,
            List<Integer> work, List<MeaningEntry> entries, Set<Integer> translatedIndices,
            AtomicInteger changed, boolean collectRetry, String cancelTag, DerivedLayerRun run,
            GoogleRunStats stats, int maxLines, int maxChars
    ) {
        List<Integer> retry = new ArrayList<>();
        if (workerSnapshot == null || work == null || work.isEmpty()) return retry;
        for (List<GoogleEnhancer.BatchLine> batch
                : translationBatches(workerSnapshot, work, maxLines, maxChars)) {
            if (batch.isEmpty()) continue;
            // Stop between batches as soon as this run is retired; the current call is cancelled
            // separately by whoever retired it.
            if (!run.isNewest()) break;
            LyricPipelineMetrics.increment(LyricPipelineMetrics.Counter.MEANING_PROVIDER_CALL);
            GoogleEnhancer.BatchResult result = provider.translate(context, http, processingVersion,
                    id, sourceLang, targetLang, batch, cancelTag);
            if (stats != null) stats.record(result);
            for (GoogleEnhancer.BatchLine item : batch) {
                if (item == null || item.index < 0 || item.index >= workerSnapshot.lines.size()) continue;
                LyricsLine line = workerSnapshot.lines.get(item.index);
                if (line == null) continue;
                String value = result.translations.get(item.index);
                boolean fromCache = result.cachedIndices.contains(item.index);
                if (!provider.shouldDisplay(line.text, value)) {
                    if (collectRetry && !fromCache) retry.add(item.index);
                    continue;
                }
                CanonicalRow row = run.base.rowAt(item.index);
                if (row != null) entries.add(new MeaningEntry(row.rowId, value, targetLang));
                translatedIndices.add(item.index);
                changed.incrementAndGet();
            }
        }
        return retry;
    }

    static boolean translationPassComplete(boolean requested, List<Integer> work, Set<Integer> translatedIndices) {
        return !requested || work == null || work.isEmpty()
                || (translatedIndices != null && translatedIndices.containsAll(work));
    }

    private List<List<GoogleEnhancer.BatchLine>> translationBatches(LyricsDocument doc, List<Integer> work) {
        return translationBatches(doc, work, TRANSLATION_BATCH_MAX_LINES,
                TRANSLATION_BATCH_MAX_CHARS);
    }

    private List<List<GoogleEnhancer.BatchLine>> translationBatches(
            LyricsDocument doc, List<Integer> work, int maxLines, int maxChars) {
        List<List<GoogleEnhancer.BatchLine>> batches = new ArrayList<>();
        List<GoogleEnhancer.BatchLine> current = new ArrayList<>();
        int currentChars = 0;
        for (int index : work) {
            if (doc == null || index < 0 || index >= doc.lines.size()) continue;
            LyricsLine line = doc.lines.get(index);
            if (line == null || isBlank(line.text)) continue;
            int lineChars = safe(line.text).length() + 14;
            if (!current.isEmpty()
                    && (current.size() >= Math.max(1, maxLines)
                    || currentChars + lineChars > Math.max(128, maxChars))) {
                batches.add(current);
                current = new ArrayList<>();
                currentChars = 0;
            }
            current.add(new GoogleEnhancer.BatchLine(index, line.text));
            currentChars += lineChars;
        }
        if (!current.isEmpty()) batches.add(current);
        return batches.isEmpty() ? Collections.<List<GoogleEnhancer.BatchLine>>emptyList() : batches;
    }

    static List<Integer> untranslated(List<Integer> requested, Set<Integer> translated) {
        List<Integer> missing = new ArrayList<>();
        if (requested == null) return missing;
        for (Integer index : requested) {
            if (index != null && (translated == null || !translated.contains(index))) {
                missing.add(index);
            }
        }
        return missing;
    }

    private void logGoogleSettlement(GoogleRunStats stats, int translated, boolean complete,
                                     long durationMs) {
        if (stats == null) return;
        String result = complete ? "complete" : translated > 0 ? "partial" : "empty";
        String reason = safe(stats.lastReason);
        if (complete && !reason.isEmpty()) reason = "recovered_" + reason;
        XposedBridge.log(TAG + " google settled result=" + result
                + " requested=" + stats.requested
                + " translated=" + translated
                + " cacheHits=" + stats.cacheHits.size()
                + " networkAttempts=" + stats.networkAttempts
                + " status=" + stats.lastStatus
                + " reason=" + reason
                + " durationMs=" + Math.max(0L, durationMs));
    }

    private static final class GoogleRunStats {
        final int requested;
        final long startedAtMs = SystemClock.elapsedRealtime();
        final Set<Integer> cacheHits = new HashSet<>();
        int networkAttempts;
        int lastStatus;
        String lastReason = "";

        GoogleRunStats(int requested) {
            this.requested = Math.max(0, requested);
        }

        void record(GoogleEnhancer.BatchResult result) {
            if (result == null) {
                lastReason = "null_result";
                return;
            }
            cacheHits.addAll(result.cachedIndices);
            networkAttempts += Math.max(0, result.networkAttempts);
            if (result.httpStatus > 0) lastStatus = result.httpStatus;
            if (!isBlank(result.failureReason)) lastReason = result.failureReason;
        }
    }


    /**
     * Retires the current run and aborts its in-flight requests. Called on a track change, so a
     * skipped track stops costing provider calls instead of merely having its callbacks ignored.
     */
    public void cancelActive() {
        laneSequence.incrementAndGet();
        String retired = activeTag;
        activeTag = "";
        if (!retired.isEmpty()) provider.cancel(http, retired);
        AiSignal signal = aiSignal;
        aiSignal = null;
        // Aborts the socket as well as the callback, so skipping a track stops the transfer. It
        // still cannot promise the provider did not bill for what it had already served.
        if (signal != null) signal.abort("track_change");
    }

    /** The artifact for what this lane translated, built from entries collected while it worked. */
    private MeaningArtifact artifactOf(DerivedLayerRun run, List<MeaningEntry> entries, boolean complete) {
        if (entries.isEmpty()) return null;
        return new MeaningArtifact(run.canonicalDigest(), run.configId(),
                new LayerProvenance(LayerAuthority.MACHINE, provider.backendId(), run.configId(),
                        System.currentTimeMillis()),
                new ArrayList<>(entries), !complete);
    }

    static Set<String> requiredRowIds(CanonicalBase base, LyricsDocument document,
                                      String sourceLang, String targetLang) {
        Set<String> rows = new LinkedHashSet<>();
        if (base == null || document == null) return rows;
        for (CanonicalRow row : base.rows) {
            if (row == null || row.index < 0 || row.index >= document.lines.size()) continue;
            LyricsLine line = document.lines.get(row.index);
            if (line == null || line.interlude || isBlank(line.text)) continue;
            String provider = ProviderTranslationResolver.resolve(line.text,
                    line.providerTranslatedText, line.providerTranslationLanguage, targetLang);
            if (!isBlank(provider)) continue;
            if (SpicyProcessing.flagsFor(line.text, sourceLang, targetLang).translationPending) {
                rows.add(row.rowId);
            }
        }
        return rows;
    }

    private static boolean isCurrent(LyricsSecondaryProcessor.CurrentGuard guard, String id,
                                     int generation, LyricsDocument snapshot) {
        return guard == null || guard.isCurrent(id, generation, snapshot);
    }

    private void post(DerivedLayerRun run, String id, int generation, LyricsDocument snapshot,
                      LyricsSecondaryProcessor.CurrentGuard guard, Runnable action) {
        handler.post(() -> {
            if (!run.accepts(guard, id, generation, snapshot)) return;
            action.run();
        });
    }
}
