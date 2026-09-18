package com.eza.spicyex.lyrics;

import android.content.Context;
import android.os.Handler;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.eza.spicyex.Diagnostics;
import com.eza.spicyex.lyrics.ai.AiCancelledException;
import com.eza.spicyex.lyrics.ai.AiContract;
import com.eza.spicyex.lyrics.ai.AiLiveMonitor;
import com.eza.spicyex.lyrics.ai.AiRunOutcome;
import com.eza.spicyex.lyrics.ai.AiRunMonitor;
import com.eza.spicyex.lyrics.ai.AiRequestLiveState;
import com.eza.spicyex.lyrics.ai.AiRuntimeFailureLog;
import com.eza.spicyex.lyrics.ai.AiSettings;
import com.eza.spicyex.lyrics.ai.AiSignal;
import com.eza.spicyex.lyrics.ai.AiSoundOverlay;
import com.eza.spicyex.lyrics.ai.AiSoundRun;
import com.eza.spicyex.lyrics.ai.AiText;
import com.eza.spicyex.lyrics.reading.ReadingPlanFactory;
import com.eza.spicyex.lyrics.session.CanonicalRow;
import com.eza.spicyex.lyrics.session.LayerAuthority;
import com.eza.spicyex.lyrics.session.LayerFailure;
import com.eza.spicyex.lyrics.session.LayerKind;
import com.eza.spicyex.lyrics.session.LayerProvenance;
import com.eza.spicyex.lyrics.session.LyricPipelineMetrics;
import com.eza.spicyex.lyrics.session.SoundArtifact;
import com.eza.spicyex.lyrics.session.SoundEntry;

import com.eza.spicyex.xposed.XpLog;
import okhttp3.OkHttpClient;
import static com.eza.spicyex.lyrics.LyricUtils.isBlank;
import static com.eza.spicyex.lyrics.LyricUtils.safe;

/**
 * The Sound lane: deterministic on-device readings, with an optional network romanization fallback.
 *
 * <p>Owns its own executors, run bookkeeping, and completion. It starts from the canonical base and
 * never waits for Meaning: its network fan-out completes through a counter, so the lane executor is
 * never parked on I/O and translation work is free to run in parallel.
 */
public final class LyricsSoundLane {
    private static final String TAG = "[SpotifyPlusSoundLane]";
    /** Diagnostic-capture component for the AI side of this lane. */
    private static final String AI_COMPONENT = "ai_sound";

    private final Context context;
    private final OkHttpClient http;
    private final ExecutorService laneExecutor;
    private final ExecutorService networkWorkers;
    /** AI gap-filling runs here, never on a reading thread. */
    private final ExecutorService aiExecutor;
    /** Cancels the AI pass in flight, if one was started. */
    private volatile AiSignal aiSignal;
    private final Handler handler;
    private final int processingVersion;
    /** Retires earlier runs of this lane: only the newest sequence may publish. */
    private final AtomicLong laneSequence = new AtomicLong();
    /** Call tag of the run currently allowed to publish; empty when the lane is idle. */
    private volatile String activeTag = "";

    public LyricsSoundLane(Context context, OkHttpClient http, ExecutorService laneExecutor,
                           ExecutorService networkWorkers, ExecutorService aiExecutor,
                           Handler handler, int processingVersion) {
        this.context = context;
        this.http = http;
        this.laneExecutor = laneExecutor;
        this.networkWorkers = networkWorkers;
        this.aiExecutor = aiExecutor == null ? networkWorkers : aiExecutor;
        this.handler = handler;
        this.processingVersion = processingVersion;
    }

    /**
     * @param showRomanization whether the Sound layer is selected at all
     * @return true when a run was started; false when the layer had nothing to do
     */
    public boolean start(
            String id,
            int generation,
            LyricsDocument snapshot,
            boolean showRomanization,
            RomanizationOptions opts,
            SoundArtifact displayedSound,
            String effectiveSourceLang,
            boolean explicitAiRequest,
            LyricsSecondaryProcessor.CurrentGuard currentGuard,
            LyricsSecondaryProcessor.Callback callback
    ) {
        if (snapshot == null || snapshot.lines.isEmpty()) return false;
        final boolean wanted = snapshot.romanizationPending && showRomanization;
        LyricsDocument workerSnapshot = LyricsDocument.copyOf(snapshot);
        if (workerSnapshot == null || workerSnapshot.lines.isEmpty()) return false;
        String fullText = LyricsDocumentProcessor.collectText(workerSnapshot);

        List<Integer> localWork = new ArrayList<>();
        List<Integer> networkWork = new ArrayList<>();
        if (wanted) {
            for (int i = 0; i < workerSnapshot.lines.size(); i++) {
                LyricsLine line = workerSnapshot.lines.get(i);
                if (line == null || isBlank(line.text) || line.interlude) continue;
                if (LyricsLocalRomanizer.shouldLocalRomanize(
                        showRomanization, opts.chineseMode, workerSnapshot, line, fullText)) {
                    localWork.add(i);
                }
            }
            for (int i = 0; i < workerSnapshot.lines.size(); i++) {
                LyricsLine line = workerSnapshot.lines.get(i);
                if (line == null || isBlank(line.text) || line.interlude) continue;
                if (LyricsLocalRomanizer.shouldGoogleRomanize(showRomanization, line)) networkWork.add(i);
            }
        }

        final DerivedLayerRun run = DerivedLayerRun.begin(context, LayerKind.SOUND, workerSnapshot, laneSequence);
        retirePrevious(run);

        if (localWork.isEmpty() && networkWork.isEmpty()) {
            AiSettings settings = new AiSettings(context);
            boolean aiConfigured = settings.soundLayerEnabled() && settings.isConfigured();
            if (aiConfigured) {
                startAiGapFill(run, id, generation, snapshot, displayedSound, displayedSound, settings,
                        explicitAiRequest || settings.pronunciationAutomatic(), currentGuard, callback);
                return true;
            }
            post(run, id, generation, snapshot, currentGuard,
                    () -> callback.complete(LayerKind.SOUND, null, LayerFailure.NONE, "", 0));
            return false;
        }

        final long startedAtMs = SystemClock.elapsedRealtime();
        laneExecutor.execute(() -> {
            AtomicInteger changed = new AtomicInteger();
            Set<Integer> locallyRomanized = new HashSet<>();
            // The lane's output. It no longer writes to the document at all: the session holds the
            // artifact and publication composes from it.
            List<SoundEntry> entries = Collections.synchronizedList(new ArrayList<>());

            for (int index : localWork) {
                if (!run.accepts(currentGuard, id, generation, snapshot)) return;
                LyricsLine source = workerSnapshot.lines.get(index);
                LyricsLine line = LyricsLine.copyOf(source);
                String local = LyricsLocalRomanizer.romanizeLine(opts, workerSnapshot, line, fullText);
                if (!isBlank(local) && !local.equals(line.text)
                        && !SpicyTextDetection.hasRomanizableScript(local)) {
                    // A RenderPlan owns local Korean/Japanese/Chinese output. Keep the legacy
                    // line slot empty so the same reading cannot mount a duplicate row.
                    line.romanizedText = line.readingRenderPlan == null ? local : "";
                    LyricsLocalRomanizer.populateLocalSegmentRomanization(opts, workerSnapshot, line, fullText);
                    resolveReadingProjection(line);
                    addEntry(entries, run, index, line);
                    locallyRomanized.add(index);
                    changed.incrementAndGet();
                } else if (line.japaneseReading != source.japaneseReading
                        || !safe(line.chineseMode).equals(safe(source.chineseMode))) {
                    resolveReadingProjection(line);
                    addEntry(entries, run, index, line);
                }
            }

            boolean hasLocalOutput;
            synchronized (entries) {
                hasLocalOutput = !entries.isEmpty();
            }
            if (hasLocalOutput) {
                // Show what is ready before the slower network pass. The partial artifact goes with
                // it, so the session holds everything that is on screen.
                post(run, id, generation, snapshot, currentGuard,
                        () -> callback.rerender(LayerKind.SOUND, artifactOf(run, entries, true),
                                "Local romanization ready"));
            }

            if (networkWork.isEmpty()) {
                finish(run, id, generation, snapshot, currentGuard, callback, entries,
                        changed.get(), startedAtMs, explicitAiRequest);
                return;
            }
            runNetworkPass(run, id, generation, snapshot, workerSnapshot, showRomanization,
                    effectiveSourceLang, networkWork, locallyRomanized, changed, currentGuard,
                    callback, entries, startedAtMs, explicitAiRequest);
        });
        return true;
    }

    /**
     * Fans reading fallback requests out to the lane's own workers. Completion is counted, never
     * awaited: the lane executor stays free and the Meaning lane is unaffected either way.
     */
    private void runNetworkPass(
            DerivedLayerRun run,
            String id, int generation, LyricsDocument snapshot, LyricsDocument workerSnapshot,
            boolean showRomanization, String effectiveSourceLang, List<Integer> networkWork,
            Set<Integer> locallyRomanized, AtomicInteger changed,
            LyricsSecondaryProcessor.CurrentGuard currentGuard,
            LyricsSecondaryProcessor.Callback callback, List<SoundEntry> entries,
            long startedAtMs, boolean explicitAiRequest
    ) {
        final AtomicInteger remaining = new AtomicInteger(networkWork.size());
        final AtomicInteger done = new AtomicInteger();
        final int total = networkWork.size();
        for (int index : networkWork) {
            networkWorkers.execute(() -> {
                try {
                    if (!run.isNewest() || !isCurrent(currentGuard, id, generation, snapshot)) return;
                    LyricsLine line = workerSnapshot.lines.get(index);
                    boolean needRomanize = !locallyRomanized.contains(index)
                            && LyricsLocalRomanizer.shouldGoogleRomanize(showRomanization, line);
                    if (!needRomanize) return;
                    LyricPipelineMetrics.increment(LyricPipelineMetrics.Counter.SOUND_PROVIDER_CALL);
                    GoogleEnhancer.Enhancement enhancement = GoogleEnhancer.enhanceLine(context, http,
                            processingVersion, id, line.detection != null && line.detection.hasLanguage()
                                    ? line.detection.language : effectiveSourceLang, "", line.text, true, false,
                            run.tag);
                    if (isBlank(enhancement.romanized) || enhancement.romanized.equals(line.text)
                            || SpicyTextDetection.hasRomanizableScript(enhancement.romanized)) {
                        return;
                    }
                    CanonicalRow row = run.base.rowAt(index);
                    if (row != null) {
                        entries.add(SoundEntry.line(row.rowId, enhancement.romanized,
                                safe(line.chineseMode)));
                    }
                    changed.incrementAndGet();
                } catch (Throwable t) {
                    XpLog.log(TAG + " reading fallback line failed: " + t.getClass().getSimpleName());
                } finally {
                    int processed = done.incrementAndGet();
                    if (processed % 12 == 0) {
                        post(run, id, generation, snapshot, currentGuard,
                                () -> callback.progress("Network romanization... " + processed + "/" + total));
                    }
                    if (remaining.decrementAndGet() == 0) {
                        finish(run, id, generation, snapshot, currentGuard, callback, entries,
                                changed.get(), startedAtMs, explicitAiRequest);
                    }
                }
            });
        }
    }

    /**
     * Settles the line's reading projection before it is read twice.
     *
     * <p>A line without a plan gets a synthesized line-level one, and a plan owns the displayed
     * text so the legacy string is cleared beside it. Doing this on the line — rather than inside
     * the patch on its way to the document — means the patch and the artifact describe the same
     * projection instead of each deriving their own.
     */
    private static void resolveReadingProjection(LyricsLine line) {
        if (line == null || line.readingRenderPlan != null) return;
        line.readingRenderPlan = ReadingPlanFactory.lineFallback(
                line, safe(line.romanizedText), "local");
        if (line.readingRenderPlan != null) line.romanizedText = "";
    }

    /** Records one row's reading projection as the lane produces it. */
    private static void addEntry(List<SoundEntry> entries, DerivedLayerRun run, int index,
                                 LyricsLine line) {
        CanonicalRow row = run.base.rowAt(index);
        if (row == null) return;
        SoundEntry entry = SoundEntry.fromLine(row, line);
        if (entry != null) entries.add(entry);
    }

    /**
     * The artifact for what the lane has produced so far.
     *
     * <p>Built from entries collected during processing rather than read back off the document, so
     * the artifact is the lane's own output rather than an observation of what it wrote somewhere.
     */
    private static SoundArtifact artifactOf(DerivedLayerRun run, List<SoundEntry> entries,
                                            boolean partial) {
        List<SoundEntry> snapshot;
        synchronized (entries) {
            if (entries.isEmpty()) return null;
            snapshot = new ArrayList<>(entries);
        }
        return new SoundArtifact(run.canonicalDigest(), run.configId(),
                new LayerProvenance(LayerAuthority.DETERMINISTIC, "local-romanizer", run.configId(),
                        System.currentTimeMillis()),
                snapshot, partial);
    }

    private void finish(DerivedLayerRun run, String id, int generation, LyricsDocument snapshot,
                        LyricsSecondaryProcessor.CurrentGuard currentGuard,
                        LyricsSecondaryProcessor.Callback callback, List<SoundEntry> entries,
                        int changed, long startedAtMs, boolean explicitAiRequest) {
        LyricPipelineMetrics.increment(LyricPipelineMetrics.Counter.SOUND_PROCESSED);
        LyricPipelineMetrics.record(LyricPipelineMetrics.Timing.SOUND_PROCESSING,
                SystemClock.elapsedRealtime() - startedAtMs);
        final SoundArtifact local = artifactOf(run, entries, false);
        AiSettings settings = new AiSettings(context);
        boolean aiConfigured = settings.soundLayerEnabled() && settings.isConfigured();
        if (!aiConfigured) {
            post(run, id, generation, snapshot, currentGuard,
                    () -> callback.complete(LayerKind.SOUND, local, LayerFailure.NONE,
                            "Enhanced " + changed + " reading fields", changed));
            return;
        }
        // Keep deterministic/Google output visible while the model fills only its gaps. The final
        // completion below clears the processing state whether AI succeeds, reuses, or declines.
        if (local != null && !local.isEmpty()) {
            post(run, id, generation, snapshot, currentGuard,
                    () -> callback.rerender(LayerKind.SOUND, local,
                            "Reading baseline ready"));
        }
        startAiGapFill(run, id, generation, snapshot, local, local, settings,
                explicitAiRequest || settings.pronunciationAutomatic(), currentGuard, callback);
    }

    /**
     * Fills whatever the engines could not read, and publishes again when it lands.
     *
     * <p>A second publication rather than a delayed first one: the deterministic reading is already
     * correct for the rows it covers, and holding it back so an uncovered row can be filled would
     * make every song with one gap feel as slow as a model call.
     */
    private void startAiGapFill(final DerivedLayerRun run, final String id, final int generation,
                                final LyricsDocument snapshot, final SoundArtifact baseline,
                                final SoundArtifact fallback,
                                final AiSettings settings, final boolean allowProviderRequest,
                                final LyricsSecondaryProcessor.CurrentGuard currentGuard,
                                final LyricsSecondaryProcessor.Callback callback) {
        final String orthography = AiContract.ORTHOGRAPHY_LATIN;
        final AiSignal signal = new AiSignal();
        aiSignal = signal;
        if (allowProviderRequest) {
            AiRequestLiveState.begin(LayerKind.SOUND, run.canonicalDigest(), run.tag);
            Diagnostics.event(AI_COMPONENT, "request_started",
                    Diagnostics.context("provider", settings.providerId()));
        }
        final AiRunMonitor monitor = allowProviderRequest
                ? new AiLiveMonitor(LayerKind.SOUND, run.canonicalDigest(), run.tag)
                : null;
        aiExecutor.execute(new Runnable() {
            @Override public void run() {
                AiSoundRun.Result result;
                LayerFailure aiFailure = LayerFailure.NONE;
                try {
                    if (!run.accepts(currentGuard, id, generation, snapshot)) return;
                    result = AiSoundRun.run(context, settings, run.base, snapshot, baseline,
                            orthography, settings.soundUsesBaseline(), allowProviderRequest, signal,
                            monitor);
                    if (result != null && result.outcome != null
                            && result.outcome.kind == AiRunOutcome.Kind.FAILED) {
                        aiFailure = result.outcome.failure;
                        AiRequestLiveState.fail(LayerKind.SOUND, run.canonicalDigest(), run.tag,
                                result.outcome.failureToken, result.outcome.failure.httpStatus,
                                result.outcome.failureDetail);
                        recordAiOutcome("request_failed", "failed",
                                result.outcome.failureToken,
                                result.outcome.failure.httpStatus);
                        XpLog.log(TAG + " ai reading outcome=failed token="
                                + result.outcome.failureToken + " status="
                                + result.outcome.failure.httpStatus + " rule="
                                + result.outcome.failureDetail);
                    } else if (result != null && result.outcome != null
                            && (result.outcome.kind == AiRunOutcome.Kind.COMPLETED
                            || result.outcome.kind == AiRunOutcome.Kind.REUSED)) {
                        recordAiOutcome("request_settled",
                                result.outcome.kind.name().toLowerCase(java.util.Locale.ROOT),
                                "", 0);
                        XpLog.log(TAG + " ai reading outcome="
                                + result.outcome.kind.name().toLowerCase(java.util.Locale.ROOT)
                                + " durable=" + result.outcome.durable);
                    }
                } catch (AiCancelledException cancelled) {
                    AiRequestLiveState.cancel(LayerKind.SOUND, run.canonicalDigest(), run.tag);
                    recordAiOutcome("request_settled", "cancelled", "", 0);
                    return;
                } catch (Throwable failure) {
                    XpLog.log(TAG + " ai reading failed: "
                            + AiRuntimeFailureLog.describe(failure));
                    result = null;
                    aiFailure = new LayerFailure(LayerFailure.Reason.UNAVAILABLE,
                            "runtime_unavailable", 0);
                    AiRequestLiveState.fail(LayerKind.SOUND, run.canonicalDigest(), run.tag,
                            "runtime_unavailable", 0, failure.getClass().getSimpleName());
                    recordAiOutcome("request_failed", "failed", "runtime_unavailable", 0);
                }
                AiRequestLiveState.complete(LayerKind.SOUND, run.canonicalDigest(), run.tag);
                if (result == null || !result.hasArtifact()) {
                    final LayerFailure finalFailure = aiFailure;
                    post(run, id, generation, snapshot, currentGuard, new Runnable() {
                        @Override public void run() {
                            callback.complete(LayerKind.SOUND, fallback, finalFailure,
                                    "AI reading unavailable", 0);
                        }
                    });
                    return;
                }
                // Composed here rather than published alone: the overlay covers gaps only, and the
                // rows the engines read must arrive in the same artifact that replaces theirs.
                final SoundArtifact composed = new SoundArtifact(run.canonicalDigest(),
                        run.configId(), result.artifact.provenance,
                        AiSoundOverlay.compose(fallback == null ? null : entriesOf(fallback),
                                entriesOf(result.artifact)), false);
                final int filled = result.artifact.size();
                post(run, id, generation, snapshot, currentGuard, new Runnable() {
                    @Override public void run() {
                        callback.complete(LayerKind.SOUND, composed, LayerFailure.NONE,
                                "AI read " + filled + " lines", filled);
                    }
                });
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
                "provider", new AiSettings(context).providerId(),
                "result", result,
                "reason", AiText.nz(reason),
                "status", httpStatus > 0 ? String.valueOf(httpStatus) : ""));
    }

    private static List<SoundEntry> entriesOf(SoundArtifact artifact) {
        List<SoundEntry> out = new ArrayList<>();
        if (artifact == null) return out;
        for (String rowId : artifact.rowIds()) {
            SoundEntry entry = artifact.sound(rowId);
            if (entry != null) out.add(entry);
        }
        return out;
    }

    /** Re-runs on-device readings only, e.g. after a romanization mode cycle. */
    public void reprocessLocal(
            LyricsDocument snapshot,
            boolean showRomanization,
            RomanizationOptions opts,
            String reason,
            LyricsSecondaryProcessor.CurrentGuard currentGuard,
            LyricsSecondaryProcessor.LocalCallback callback
    ) {
        if (snapshot == null || snapshot.lines.isEmpty()) return;
        LyricsDocument workerSnapshot = LyricsDocument.copyOf(snapshot);
        if (workerSnapshot == null || workerSnapshot.lines.isEmpty()) return;
        String fullText = LyricsDocumentProcessor.collectText(workerSnapshot);
        // A rapid mode cycle must not let an earlier pass land after a later one.
        final DerivedLayerRun run = DerivedLayerRun.begin(context, LayerKind.SOUND, workerSnapshot, laneSequence);
        retirePrevious(run);
        laneExecutor.execute(() -> {
            AtomicInteger changed = new AtomicInteger();
            LyricsProcessingPatch patch = new LyricsProcessingPatch();
            try {
                if (showRomanization) {
                    for (int index = 0; index < workerSnapshot.lines.size(); index++) {
                        LyricsLine source = workerSnapshot.lines.get(index);
                        if (source == null || isBlank(source.text) || source.interlude) continue;
                        LyricsLine line = LyricsLine.copyOf(source);
                        String before = safe(line.romanizedText);
                        LyricsLocalRomanizer.clearSegmentRomanization(line);
                        String local = LyricsLocalRomanizer.romanizeLine(opts, workerSnapshot, line, fullText);
                        if (!isBlank(local) && !local.equals(line.text)
                                && !SpicyTextDetection.hasRomanizableScript(local)) {
                            line.romanizedText = line.readingRenderPlan == null ? local : "";
                            if (!before.equals(local)) changed.incrementAndGet();
                        }
                        LyricsLocalRomanizer.populateLocalSegmentRomanization(opts, workerSnapshot, line, fullText);
                        patch.addLinePatch(LyricsProcessingPatch.soundLine(index, line));
                    }
                }
            } catch (Throwable t) {
                XpLog.log(TAG + " local mode reprocess failed: " + t.getClass().getSimpleName());
            }
            patch.changed = changed.get();
            handler.post(() -> {
                boolean current = run.isNewest()
                        && (currentGuard == null || currentGuard.isCurrent("", 0, snapshot));
                if (current) {
                    patch.applyTo(snapshot);
                    LyricsDocumentProcessor.saveSoundArtifact(context, snapshot, opts,
                            !snapshot.romanizationPending);
                }
                callback.complete(reason, changed.get(), current);
            });
        });
    }


    /**
     * Retires the current run and aborts its in-flight requests. Called on a track change, so a
     * skipped track stops costing requests instead of merely having its callbacks ignored.
     */
    public void cancelActive() {
        AiSignal signal = aiSignal;
        aiSignal = null;
        if (signal != null) signal.abort("track_change");
        laneSequence.incrementAndGet();
        String retired = activeTag;
        activeTag = "";
        if (!retired.isEmpty()) GoogleEnhancer.cancelTagged(http, retired);
    }

    private void retirePrevious(DerivedLayerRun run) {
        String retired = activeTag;
        activeTag = run.tag;
        if (!retired.isEmpty()) GoogleEnhancer.cancelTagged(http, retired);
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
