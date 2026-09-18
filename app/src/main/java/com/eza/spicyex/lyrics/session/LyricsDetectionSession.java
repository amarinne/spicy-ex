package com.eza.spicyex.lyrics.session;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.eza.spicyex.lyrics.LatinLanguageGate;
import com.eza.spicyex.lyrics.ProcessedLyricsCache;

import com.eza.spicyex.xposed.XpLog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Session-owned language detection over one canonical base.
 *
 * <p>Detection runs once per canonical row and is persisted as a {@link DetectionArtifact}. A
 * restored row is reused only when its stored source text still matches, so a changed lyric line
 * invalidates exactly that line. Concurrent requests for the same canonical digest coalesce onto
 * one run, and a completion whose track generation is stale is dropped rather than published.
 *
 * <p>This class is the only producer of detection in the process. Render surfaces and the derived
 * lanes consume {@link DetectionResult} values already attached to the document; they never call
 * the detector.
 */
public final class LyricsDetectionSession {
    private static final String TAG = "[SpotifyPlusDetectionSession]";
    /** In-memory artifacts for recently seen digests; cleared under memory pressure. */
    private static final int MEMORY_LRU_CAPACITY = 4;

    /** Durable artifact storage. */
    public interface Cache {
        DetectionArtifact restore(Context context, CanonicalBase base);
        boolean save(Context context, CanonicalBase base, DetectionArtifact artifact);
        /** Detection for a non-canonical string (provider translation), or null when none. */
        default DetectionResult restoreText(Context context, String text) {
            return null;
        }
        /** Persists detection for a non-canonical string. */
        default boolean saveText(Context context, String text, DetectionResult result) {
            return false;
        }
    }

    /** One-row detection. The flavor gate supplies the production implementation. */
    public interface Detector {
        DetectionResult detect(String text, DetectionResult known);
    }

    /** Main-thread delivery. */
    public interface Poster {
        void post(Runnable runnable);
    }

    /** Result acceptance: the request is still the current track generation. */
    public interface Guard {
        boolean isCurrent(CanonicalBase base, int generation);
    }

    public interface Callback {
        /** Fired on the poster thread; {@code artifact} is null when no detection is available. */
        void onDetectionReady(CanonicalBase base, DetectionArtifact artifact);
    }

    private final Context context;
    private final Executor work;
    private final Poster poster;
    private final Cache cache;
    private final Detector detector;
    private final Map<String, Inflight> inflight = new HashMap<>();
    /**
     * Auxiliary texts being detected, and the requests waiting on each.
     *
     * <p>Auxiliary detection is independent of canonical coalescing: a cached canonical artifact
     * still lets new provider texts enter detection, and duplicate concurrent requests for the
     * same text share one pass.
     */
    private final Map<String, List<Request>> auxiliaryWaiters = new HashMap<>();
    private final java.util.Set<String> auxiliaryInFlight = new java.util.HashSet<>();
    private final LinkedHashMap<String, DetectionArtifact> memory =
            new LinkedHashMap<String, DetectionArtifact>(8, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, DetectionArtifact> eldest) {
                    return size() > MEMORY_LRU_CAPACITY;
                }
            };

    /** Incremented on cancel; a completing run from a previous epoch is stale. */
    private long epoch;

    /** Production wiring: Android cache plus the flavor detection gate. */
    public LyricsDetectionSession(Context context, Executor work) {
        this(context, work, new HandlerPoster(), new AndroidCache(),
                (text, known) -> LatinLanguageGate.detect(text, known));
    }

    LyricsDetectionSession(Context context, Executor work, Poster poster, Cache cache,
                           Detector detector) {
        this.context = context;
        this.work = work;
        this.poster = poster;
        this.cache = cache;
        this.detector = detector;
    }

    /**
     * Starts or joins detection for {@code base}.
     *
     * <p>When an identical digest is already running, the callback attaches to that run instead of
     * starting a second one. A completed in-memory artifact answers without any work.
     */
    public void start(CanonicalBase base, int generation, Guard guard, Callback callback) {
        start(base, java.util.Collections.<String>emptyList(), generation, guard, callback);
    }

    /**
     * Starts or joins detection for {@code base} plus auxiliary strings.
     *
     * <p>Canonical rows coalesce per digest, and a complete in-memory artifact answers without
     * recomputing them. Auxiliary texts are tracked separately: each request still enqueues the
     * provider texts the durable store does not already cover, even when canonical detection is
     * cached, and the callback fires only once both parts are ready.
     *
     * <p>Auxiliary strings are provider-translation texts: they are not canonical rows, so they are
     * detected under a text key and persisted separately. A translation's language can then be
     * proven by its own detection rather than the original lyric's.
     */
    public void start(CanonicalBase base, java.util.List<String> auxiliaryTexts, int generation,
                      Guard guard, Callback callback) {
        if (base == null || base.isEmpty() || callback == null) return;
        Request request = new Request(generation, guard, callback);
        List<String> missingAuxiliary = missingAuxiliaryTexts(auxiliaryTexts);
        Inflight scheduled = null;
        synchronized (this) {
            DetectionArtifact cached = memory.get(base.digest);
            if (cached != null && !cached.partial) {
                request.base = base;
                request.artifact = cached;
                request.canonicalReady = true;
            } else {
                Inflight running = inflight.get(base.digest);
                if (running != null) {
                    running.requests.add(request);
                } else {
                    Inflight created = new Inflight(epoch);
                    created.requests.add(request);
                    inflight.put(base.digest, created);
                    scheduled = created;
                }
            }
            for (String text : missingAuxiliary) {
                enqueueAuxiliaryLocked(text, request);
            }
        }
        if (scheduled != null) {
            Inflight owner = scheduled;
            work.execute(() -> run(base, owner));
        }
        if (request.canonicalReady && request.pendingAuxiliary == 0) {
            poster.post(() -> deliverIfReady(request));
        }
    }

    /** Distinct non-blank auxiliary texts with no terminal durable or in-memory result yet. */
    private List<String> missingAuxiliaryTexts(List<String> texts) {
        List<String> out = new ArrayList<>();
        if (texts == null || texts.isEmpty()) return out;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String text : texts) {
            if (text == null || text.trim().isEmpty() || !seen.add(text)) continue;
            DetectionResult known = cache.restoreText(context, text);
            if (known != null && known.isReusable()) continue;
            out.add(text);
        }
        return out;
    }

    private void enqueueAuxiliaryLocked(String text, Request request) {
        List<Request> waiters = auxiliaryWaiters.get(text);
        if (waiters == null) {
            waiters = new ArrayList<>();
            auxiliaryWaiters.put(text, waiters);
        }
        waiters.add(request);
        request.pendingAuxiliary++;
        if (auxiliaryInFlight.add(text)) {
            work.execute(() -> runAuxiliary(text));
        }
    }

    /** Detects one provider text, persisting it under its text key; waiters share one pass. */
    private void runAuxiliary(String text) {
        DetectionResult landed;
        try {
            DetectionResult known = cache.restoreText(context, text);
            if (known != null && known.isReusable()) {
                LyricPipelineMetrics.increment(LyricPipelineMetrics.Counter.DETECTION_ROW_REUSED);
                landed = known;
            } else {
                LyricPipelineMetrics.increment(LyricPipelineMetrics.Counter.DETECTION_ROW_DETECTED);
                DetectionResult result = detector.detect(text, known);
                landed = result == null ? DetectionResult.unknown("", text)
                        : result.withRow("", text);
                cache.saveText(context, text, landed);
            }
        } catch (Throwable t) {
            XpLog.log(TAG + " auxiliary detection failed: " + t);
            landed = DetectionResult.error("", text, null);
        }
        final DetectionResult result = landed;
        poster.post(() -> completeAuxiliary(text, result));
    }

    private void run(CanonicalBase base, Inflight owner) {
        DetectionArtifact artifact = null;
        int reused = 0;
        int detected = 0;
        boolean persisted = false;
        try {
            DetectionArtifact restored = cache.restore(context, base);
            List<DetectionResult> rows = new ArrayList<>();
            boolean retryable = false;
            Map<String, DetectionResult> uniqueTexts = new HashMap<>();
            for (CanonicalRow row : base.rows) {
                if (row == null || row.text.isEmpty()) continue;
                DetectionResult known = restored == null ? null : restored.result(row.rowId);
                // Terminal results, including UNKNOWN negatives, are reused; only ERROR rows and
                // changed text are detected again.
                if (known != null && known.isReusable() && row.text.equals(known.sourceText)) {
                    LyricPipelineMetrics.increment(LyricPipelineMetrics.Counter.DETECTION_ROW_REUSED);
                    reused++;
                    rows.add(known);
                    continue;
                }
                LyricPipelineMetrics.increment(LyricPipelineMetrics.Counter.DETECTION_ROW_DETECTED);
                detected++;
                DetectionResult result = uniqueTexts.get(row.text);
                if (result == null) {
                    result = detector.detect(row.text, known);
                    if (result != null) uniqueTexts.put(row.text, result);
                }
                DetectionResult landed = result == null
                        ? DetectionResult.unknown(row.rowId, row.text)
                        : result.withRow(row.rowId, row.text);
                if (!landed.isReusable()) retryable = true;
                rows.add(landed);
            }
            if (!rows.isEmpty()) {
                boolean complete = rows.size() >= countableRows(base) && !retryable;
                DetectionResult latinAggregate = detectLatinAggregate(base, rows);
                artifact = new DetectionArtifact(base.digest,
                        DetectionArtifact.DETECTOR_POLICY_ID,
                        ContextLanguageRouter.resolve(base, rows, latinAggregate), !complete);
                persisted = cache.save(context, base, artifact);
                if (persisted) {
                    LyricPipelineMetrics.increment(
                            LyricPipelineMetrics.Counter.DETECTION_ARTIFACT_PERSISTED);
                }
            }
        } catch (Throwable t) {
            XpLog.log(TAG + " detection run failed: " + t);
        }
        XpLog.log(TAG + " detection rows reused=" + reused + " detected=" + detected
                + " persisted=" + persisted);
        final DetectionArtifact completed = artifact;
        poster.post(() -> complete(base, owner, completed));
    }

    /** Classifies each distinct Latin line once as one document-level fallback for short rows. */
    private DetectionResult detectLatinAggregate(CanonicalBase base, List<DetectionResult> rows) {
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        StringBuilder text = new StringBuilder();
        boolean unresolved = false;
        for (DetectionResult row : rows) {
            if (row.scriptClass != com.eza.spicyex.lyrics.ScriptClassifier.ScriptClass.LATIN) continue;
            if (!row.hasLanguage() && row.status != DetectionStatus.ERROR) unresolved = true;
            if (row.sourceText.trim().isEmpty() || !seen.add(row.sourceText)) continue;
            if (text.length() > 0) text.append('\n');
            text.append(row.sourceText);
        }
        if (!unresolved || text.codePoints().filter(Character::isLetter).count() < 20) return null;
        return detector.detect(text.toString(), null);
    }

    private void complete(CanonicalBase base, Inflight owner, DetectionArtifact artifact) {
        synchronized (this) {
            // Cancellation can be followed by a new run for the same digest. Only the run that
            // still owns this entry may consume its requests or publish into the memory cache.
            if (inflight.get(base.digest) != owner || owner.epoch != epoch) {
                LyricPipelineMetrics.add(LyricPipelineMetrics.Counter.DETECTION_STALE_REJECTED,
                        owner.requests.size());
                return;
            }
            inflight.remove(base.digest);
            if (artifact != null && !artifact.isEmpty()) {
                memory.put(base.digest, artifact);
            }
        }
        for (Request request : owner.requests) {
            synchronized (this) {
                request.base = base;
                request.artifact = artifact;
                request.canonicalReady = true;
            }
            deliverIfReady(request);
        }
    }

    private void completeAuxiliary(String text, DetectionResult result) {
        List<Request> waiters;
        synchronized (this) {
            auxiliaryInFlight.remove(text);
            waiters = auxiliaryWaiters.remove(text);
        }
        if (waiters == null || waiters.isEmpty()) return;
        for (Request request : waiters) {
            synchronized (this) {
                if (request.pendingAuxiliary > 0) request.pendingAuxiliary--;
            }
            deliverIfReady(request);
        }
    }

    /** Delivers exactly once, only when canonical and every auxiliary result are ready. */
    private void deliverIfReady(Request request) {
        boolean ready;
        synchronized (this) {
            ready = !request.delivered && request.canonicalReady && request.pendingAuxiliary == 0;
            if (ready) request.delivered = true;
        }
        if (!ready) return;
        boolean current = request.guard == null
                || request.guard.isCurrent(request.base, request.generation);
        if (!current) {
            LyricPipelineMetrics.increment(LyricPipelineMetrics.Counter.DETECTION_STALE_REJECTED);
            return;
        }
        request.callback.onDetectionReady(request.base, request.artifact);
    }

    /** Marks every in-flight run stale and drops the in-memory artifacts. */
    public synchronized void cancelActive() {
        epoch++;
        inflight.clear();
        auxiliaryWaiters.clear();
        memory.clear();
    }

    /** Drops in-memory artifacts only; durable detection records are untouched. */
    public synchronized void trimMemory() {
        memory.clear();
    }

    synchronized int memoryEntryCount() {
        return memory.size();
    }

    private static int countableRows(CanonicalBase base) {
        int count = 0;
        for (CanonicalRow row : base.rows) {
            if (row != null && !row.text.isEmpty()) count++;
        }
        return count;
    }

    private static final class Request {
        final int generation;
        final Guard guard;
        final Callback callback;
        CanonicalBase base;
        DetectionArtifact artifact;
        boolean canonicalReady;
        int pendingAuxiliary;
        boolean delivered;

        Request(int generation, Guard guard, Callback callback) {
            this.generation = generation;
            this.guard = guard;
            this.callback = callback;
        }
    }

    /** One digest's run plus every request attached to it. */
    private static final class Inflight {
        final long epoch;
        final List<Request> requests = new ArrayList<>();

        Inflight(long epoch) {
            this.epoch = epoch;
        }
    }

    private static final class AndroidCache implements Cache {
        @Override
        public DetectionArtifact restore(Context context, CanonicalBase base) {
            return ProcessedLyricsCache.restoreDetection(context, base);
        }

        @Override
        public boolean save(Context context, CanonicalBase base, DetectionArtifact artifact) {
            return ProcessedLyricsCache.saveDetection(context, base, artifact);
        }

        @Override
        public DetectionResult restoreText(Context context, String text) {
            return com.eza.spicyex.lyrics.ProviderTextDetectionStore.get(context, text);
        }

        @Override
        public boolean saveText(Context context, String text, DetectionResult result) {
            com.eza.spicyex.lyrics.ProviderTextDetectionStore.put(context, text, result);
            return true;
        }
    }

    private static final class HandlerPoster implements Poster {
        private final Handler handler = new Handler(Looper.getMainLooper());

        @Override
        public void post(Runnable runnable) {
            handler.post(runnable);
        }
    }
}
