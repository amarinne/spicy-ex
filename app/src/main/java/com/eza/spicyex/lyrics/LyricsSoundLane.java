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

import com.eza.spicyex.lyrics.reading.ReadingPlanFactory;
import com.eza.spicyex.lyrics.session.CanonicalRow;
import com.eza.spicyex.lyrics.session.LayerAuthority;
import com.eza.spicyex.lyrics.session.LayerKind;
import com.eza.spicyex.lyrics.session.LayerProvenance;
import com.eza.spicyex.lyrics.session.LyricPipelineMetrics;
import com.eza.spicyex.lyrics.session.SoundArtifact;
import com.eza.spicyex.lyrics.session.SoundEntry;

import de.robv.android.xposed.XposedBridge;
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

    private final Context context;
    private final OkHttpClient http;
    private final ExecutorService laneExecutor;
    private final ExecutorService networkWorkers;
    private final Handler handler;
    private final int processingVersion;
    /** Retires earlier runs of this lane: only the newest sequence may publish. */
    private final AtomicLong laneSequence = new AtomicLong();
    /** Call tag of the run currently allowed to publish; empty when the lane is idle. */
    private volatile String activeTag = "";

    public LyricsSoundLane(Context context, OkHttpClient http, ExecutorService laneExecutor,
                           ExecutorService networkWorkers, Handler handler, int processingVersion) {
        this.context = context;
        this.http = http;
        this.laneExecutor = laneExecutor;
        this.networkWorkers = networkWorkers;
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
            String effectiveSourceLang,
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
            post(run, id, generation, snapshot, currentGuard,
                    () -> callback.complete(LayerKind.SOUND, null, "", 0));
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
                    line.romanizedText = local;
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
                        changed.get(), startedAtMs);
                return;
            }
            runNetworkPass(run, id, generation, snapshot, workerSnapshot, showRomanization,
                    effectiveSourceLang, networkWork, locallyRomanized, changed, currentGuard,
                    callback, entries, startedAtMs);
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
            long startedAtMs
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
                            processingVersion, id, effectiveSourceLang, "", line.text, true, false,
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
                    XposedBridge.log(TAG + " reading fallback line failed: " + t.getClass().getSimpleName());
                } finally {
                    int processed = done.incrementAndGet();
                    if (processed % 12 == 0) {
                        post(run, id, generation, snapshot, currentGuard,
                                () -> callback.progress("Network romanization... " + processed + "/" + total));
                    }
                    if (remaining.decrementAndGet() == 0) {
                        finish(run, id, generation, snapshot, currentGuard, callback, entries,
                                changed.get(), startedAtMs);
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
                line, safe(line.romanizedText), "remoteFallback");
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
                        int changed, long startedAtMs) {
        LyricPipelineMetrics.increment(LyricPipelineMetrics.Counter.SOUND_PROCESSED);
        LyricPipelineMetrics.record(LyricPipelineMetrics.Timing.SOUND_PROCESSING,
                SystemClock.elapsedRealtime() - startedAtMs);
        post(run, id, generation, snapshot, currentGuard,
                () -> callback.complete(LayerKind.SOUND, artifactOf(run, entries, false),
                        "Enhanced " + changed + " reading fields", changed));
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
                            line.romanizedText = local;
                            if (!before.equals(local)) changed.incrementAndGet();
                        }
                        LyricsLocalRomanizer.populateLocalSegmentRomanization(opts, workerSnapshot, line, fullText);
                        patch.addLinePatch(LyricsProcessingPatch.soundLine(index, line));
                    }
                }
            } catch (Throwable t) {
                XposedBridge.log(TAG + " local mode reprocess failed: " + t.getClass().getSimpleName());
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
