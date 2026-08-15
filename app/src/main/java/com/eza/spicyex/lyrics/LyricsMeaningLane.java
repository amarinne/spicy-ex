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

import com.eza.spicyex.lyrics.session.CanonicalBase;
import com.eza.spicyex.lyrics.session.CanonicalRow;
import com.eza.spicyex.lyrics.session.LayerAuthority;
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
    private static final int TRANSLATION_BATCH_MAX_LINES = 100;
    private static final int TRANSLATION_BATCH_MAX_CHARS = 4500;

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
    private final Handler handler;
    private final int processingVersion;
    private final MeaningProvider provider;
    /** Retires earlier runs of this lane: only the newest sequence may publish. */
    private final AtomicLong laneSequence = new AtomicLong();
    /** Call tag of the run currently allowed to publish; empty when the lane is idle. */
    private volatile String activeTag = "";

    public LyricsMeaningLane(Context context, OkHttpClient http, ExecutorService laneExecutor,
                             Handler handler, int processingVersion) {
        this(context, http, laneExecutor, handler, processingVersion, new GoogleMeaningProvider());
    }

    public LyricsMeaningLane(Context context, OkHttpClient http, ExecutorService laneExecutor,
                             Handler handler, int processingVersion, MeaningProvider provider) {
        this.context = context;
        this.http = http;
        this.laneExecutor = laneExecutor;
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
            LyricsSecondaryProcessor.CurrentGuard currentGuard,
            LyricsSecondaryProcessor.Callback callback
    ) {
        if (snapshot == null || snapshot.lines.isEmpty()) return false;
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

        if (work.isEmpty()) {
            post(run, id, generation, snapshot, currentGuard,
                    () -> callback.complete(LayerKind.MEANING, null, "", 0));
            return false;
        }

        // One provider run per (canonical base, Meaning config), whichever surface asks first. A
        // second surface waits for the owner and then fills from cache instead of re-billing.
        LayerRunIdentity runIdentity = new LayerRunIdentity(safe(id), generation, 1,
                run.canonicalDigest(), LayerKind.MEANING, run.configId(), "", run.tag);
        if (!COALESCER.beginOrDefer(runIdentity, () -> {
            LyricPipelineMetrics.increment(LyricPipelineMetrics.Counter.COALESCED_RUN_JOINED);
            start(id, generation, snapshot, backend, targetLang, sourceLang, effectiveSourceLang,
                    currentGuard, callback);
        })) {
            return false;
        }

        final long startedAtMs = SystemClock.elapsedRealtime();
        laneExecutor.execute(() -> {
            AtomicInteger changed = new AtomicInteger();
            Set<Integer> translated = new HashSet<>();
            // Collected as the lane works rather than read back off the document. Provider-supplied
            // translations are deliberately absent: they are canonical source data carried by the
            // base, not something this layer produced.
            List<MeaningEntry> entries = new ArrayList<>();
            try {
                if (!run.accepts(currentGuard, id, generation, snapshot)) return;
                List<Integer> retry = translateBatchPass(id, effectiveSourceLang, targetLang,
                        workerSnapshot, work, entries, translated, changed, true, run.tag, run);
                if (!retry.isEmpty() && run.accepts(currentGuard, id, generation, snapshot)) {
                    post(run, id, generation, snapshot, currentGuard,
                            () -> callback.progress("Retrying echoed translations... " + retry.size()));
                    translateBatchPass(id, effectiveSourceLang, targetLang, workerSnapshot, retry,
                            entries, translated, changed, false, run.tag, run);
                }
            } catch (Throwable t) {
                XposedBridge.log(TAG + " translation pass failed: " + t.getClass().getSimpleName());
            } finally {
                // Release before publishing so a deferred surface re-runs against the fresh cache.
                COALESCER.finish(runIdentity);
            }

            boolean complete = translated.containsAll(work);
            boolean includes = complete
                    && (LyricsDocumentProcessor.hasDisplayedTranslation(workerSnapshot) || !translated.isEmpty());
            int finalChanged = changed.get();
            LyricPipelineMetrics.increment(LyricPipelineMetrics.Counter.MEANING_PROCESSED);
            LyricPipelineMetrics.record(LyricPipelineMetrics.Timing.MEANING_PROCESSING,
                    SystemClock.elapsedRealtime() - startedAtMs);
            post(run, id, generation, snapshot, currentGuard,
                    () -> callback.complete(LayerKind.MEANING, artifactOf(run, entries, complete),
                            "Enhanced " + finalChanged + " translation fields", finalChanged));
        });
        return true;
    }

    private List<Integer> translateBatchPass(
            String id, String sourceLang, String targetLang, LyricsDocument workerSnapshot,
            List<Integer> work, List<MeaningEntry> entries, Set<Integer> translatedIndices,
            AtomicInteger changed, boolean collectRetry, String cancelTag, DerivedLayerRun run
    ) {
        List<Integer> retry = new ArrayList<>();
        if (workerSnapshot == null || work == null || work.isEmpty()) return retry;
        for (List<GoogleEnhancer.BatchLine> batch : translationBatches(workerSnapshot, work)) {
            if (batch.isEmpty()) continue;
            // Stop between batches as soon as this run is retired; the current call is cancelled
            // separately by whoever retired it.
            if (!run.isNewest()) break;
            LyricPipelineMetrics.increment(LyricPipelineMetrics.Counter.MEANING_PROVIDER_CALL);
            GoogleEnhancer.BatchResult result = provider.translate(context, http, processingVersion,
                    id, sourceLang, targetLang, batch, cancelTag);
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
        List<List<GoogleEnhancer.BatchLine>> batches = new ArrayList<>();
        List<GoogleEnhancer.BatchLine> current = new ArrayList<>();
        int currentChars = 0;
        for (int index : work) {
            if (doc == null || index < 0 || index >= doc.lines.size()) continue;
            LyricsLine line = doc.lines.get(index);
            if (line == null || isBlank(line.text)) continue;
            int lineChars = safe(line.text).length() + 14;
            if (!current.isEmpty()
                    && (current.size() >= TRANSLATION_BATCH_MAX_LINES
                    || currentChars + lineChars > TRANSLATION_BATCH_MAX_CHARS)) {
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


    /**
     * Retires the current run and aborts its in-flight requests. Called on a track change, so a
     * skipped track stops costing provider calls instead of merely having its callbacks ignored.
     */
    public void cancelActive() {
        laneSequence.incrementAndGet();
        String retired = activeTag;
        activeTag = "";
        if (!retired.isEmpty()) provider.cancel(http, retired);
    }

    /** The artifact for what this lane translated, built from entries collected while it worked. */
    private MeaningArtifact artifactOf(DerivedLayerRun run, List<MeaningEntry> entries, boolean complete) {
        if (entries.isEmpty()) return null;
        return new MeaningArtifact(run.canonicalDigest(), run.configId(),
                new LayerProvenance(LayerAuthority.MACHINE, provider.backendId(), run.configId(),
                        System.currentTimeMillis()),
                new ArrayList<>(entries), !complete);
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
