package com.eza.spicyex.lyrics;

import android.content.Context;
import android.os.Handler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import de.robv.android.xposed.XposedBridge;
import okhttp3.OkHttpClient;
import static com.eza.spicyex.lyrics.LyricUtils.isBlank;
import static com.eza.spicyex.lyrics.LyricUtils.safe;

/** Background romanization/translation processing for loaded lyric documents. */
public final class LyricsSecondaryProcessor {
    private static final String TAG = "[SpotifyPlusSecondaryProcessor]";
    private static final int TRANSLATION_BATCH_MAX_LINES = 100;
    private static final int TRANSLATION_BATCH_MAX_CHARS = 4500;

    private final Context context;
    private final OkHttpClient http;
    private final ExecutorService processor;
    private final ExecutorService googleWorkers;
    private final Handler handler;
    private final int processingVersion;

    public LyricsSecondaryProcessor(
            Context context,
            OkHttpClient http,
            ExecutorService processor,
            ExecutorService googleWorkers,
            Handler handler,
            int processingVersion
    ) {
        this.context = context;
        this.http = http;
        this.processor = processor;
        this.googleWorkers = googleWorkers;
        this.handler = handler;
        this.processingVersion = processingVersion;
    }

    public void start(
            String id,
            int generation,
            LyricsDocument snapshot,
            boolean showRomanization,
            RomanizationOptions opts,
            String targetLang,
            String effectiveSourceLang,
            CurrentGuard currentGuard,
            Callback callback
    ) {
        if (snapshot == null || snapshot.lines == null || snapshot.lines.isEmpty() || !snapshot.processingPending) return;

        LyricsDocument workerSnapshot = LyricsDocument.copyOf(snapshot);
        if (workerSnapshot == null || workerSnapshot.lines == null || workerSnapshot.lines.isEmpty()) return;
        String fullText = LyricsDocumentProcessor.collectText(workerSnapshot);
        List<Integer> localWork = new ArrayList<>();
        List<Integer> romanNetworkWork = new ArrayList<>();
        List<Integer> translationWork = new ArrayList<>();

        if (workerSnapshot.romanizationPending) {
            for (int i = 0; i < workerSnapshot.lines.size(); i++) {
                LyricsLine line = workerSnapshot.lines.get(i);
                if (line == null || isBlank(line.text) || line.interlude) continue;
                if (LyricsLocalRomanizer.shouldLocalRomanize(showRomanization, opts.chineseMode, workerSnapshot, line, fullText)) localWork.add(i);
            }
            for (int i = 0; i < workerSnapshot.lines.size(); i++) {
                LyricsLine line = workerSnapshot.lines.get(i);
                if (line == null || isBlank(line.text) || line.interlude) continue;
                if (LyricsLocalRomanizer.shouldGoogleRomanize(showRomanization, line)) {
                    romanNetworkWork.add(i);
                }
            }
        }
        if (workerSnapshot.translationPending) {
            for (int i = 0; i < workerSnapshot.lines.size(); i++) {
                LyricsLine line = workerSnapshot.lines.get(i);
                if (line == null || isBlank(line.text) || line.interlude) continue;
                if (isBlank(line.translatedText)) {
                    translationWork.add(i);
                }
            }
        }

        if (localWork.isEmpty() && romanNetworkWork.isEmpty() && translationWork.isEmpty()) {
            LyricsProcessingPatch patch = flagsPatch(false, false, false,
                    workerSnapshot.includesRomanization, workerSnapshot.includesTranslation, 0);
            handler.post(() -> {
                if (currentGuard == null || currentGuard.isCurrent(id, generation, snapshot)) {
                    patch.applyTo(snapshot);
                }
            });
            return;
        }

        final boolean wantGoogleRomanize = workerSnapshot.romanizationPending;
        final boolean wantGoogleTranslate = workerSnapshot.translationPending;
        processor.execute(() -> {
            AtomicInteger changed = new AtomicInteger();
            Set<Integer> locallyRomanizedIndices = new HashSet<>();
            LyricsProcessingPatch localPatch = flagsPatch(
                    workerSnapshot.romanizationPending,
                    workerSnapshot.translationPending,
                    workerSnapshot.processingPending,
                    workerSnapshot.includesRomanization,
                    workerSnapshot.includesTranslation,
                    0);

            for (int index : localWork) {
                if (currentGuard != null && !currentGuard.isCurrent(id, generation, snapshot)) return;
                if (index < 0 || index >= workerSnapshot.lines.size()) continue;
                LyricsLine source = workerSnapshot.lines.get(index);
                LyricsLine line = LyricsLine.copyOf(source);
                String local = LyricsLocalRomanizer.romanizeLine(opts, workerSnapshot, line, fullText);
                if (!isBlank(local) && !local.equals(line.text) && !SpicyTextDetection.hasRomanizableScript(local)) {
                    line.romanizedText = local;
                    LyricsLocalRomanizer.populateLocalSegmentRomanization(opts, workerSnapshot, line, fullText);
                    localPatch.addLinePatch(LyricsProcessingPatch.fromLine(index, line, true, false));
                    locallyRomanizedIndices.add(index);
                    LyricCaches.putProcessingValue(context, processingVersion,
                            LyricCaches.romanizationKey(id, workerSnapshot.language, line.text), local);
                    changed.incrementAndGet();
                } else if (line.japaneseReading != source.japaneseReading || !safe(line.chineseMode).equals(safe(source.chineseMode))) {
                    localPatch.addLinePatch(LyricsProcessingPatch.fromLine(index, line, true, false));
                }
            }

            if (localPatch.hasLineChanges()) {
                localPatch.romanizationPending = false;
                localPatch.includesRomanization = true;
                localPatch.changed = changed.get();
                handler.post(() -> {
                    if (currentGuard == null || currentGuard.isCurrent(id, generation, snapshot)) {
                        localPatch.applyTo(snapshot);
                        if (changed.get() > 0) callback.rerender("Local romanization ready");
                    }
                });
            }

            if (romanNetworkWork.isEmpty() && translationWork.isEmpty()) {
                LyricsProcessingPatch finalPatch = flagsPatch(false, false, false,
                        true, workerSnapshot.includesTranslation, changed.get());
                if (!localPatch.hasLineChanges()) finalPatch.includesRomanization = true;
                int finalChanged = changed.get();
                handler.post(() -> {
                    if (currentGuard == null || currentGuard.isCurrent(id, generation, snapshot)) {
                        if (localPatch.hasLineChanges()) localPatch.applyTo(snapshot);
                        finalPatch.applyTo(snapshot);
                        callback.complete("Enhanced " + finalChanged + " lyric fields", finalChanged);
                    }
                });
                return;
            }

            // Network translation is about to run (slow, per-line). Persist the romanization we just
            // computed so closing the screen before it finishes doesn't lose it — reopening restores
            // romanization from cache and only re-runs the remaining translation pass.
            handler.post(() -> {
                if (currentGuard == null || currentGuard.isCurrent(id, generation, snapshot)) {
                    if (!localPatch.hasLineChanges()) {
                        localPatch.romanizationPending = false;
                        localPatch.includesRomanization = true;
                        localPatch.applyTo(snapshot);
                    }
                    LyricsDocumentProcessor.saveProcessedCache(context, snapshot, opts, processingVersion);
                }
            });

            CountDownLatch latch = new CountDownLatch(romanNetworkWork.size());
            AtomicInteger done = new AtomicInteger();
            List<LyricsProcessingPatch.LinePatch> networkPatches = Collections.synchronizedList(new ArrayList<>());
            for (int index : romanNetworkWork) {
                googleWorkers.execute(() -> {
                    try {
                        if (currentGuard != null && !currentGuard.isCurrent(id, generation, snapshot)) return;
                        if (index < 0 || index >= workerSnapshot.lines.size()) return;
                        LyricsLine line = workerSnapshot.lines.get(index);
                        boolean needRomanize = wantGoogleRomanize
                                && !locallyRomanizedIndices.contains(index)
                                && LyricsLocalRomanizer.shouldGoogleRomanize(showRomanization, line);
                        GoogleEnhancer.Enhancement enhancement = GoogleEnhancer.enhanceLine(context, http,
                                processingVersion, id, effectiveSourceLang, targetLang, line.text,
                                needRomanize, false);
                        int localChanged = 0;
                        LyricsProcessingPatch.LinePatch linePatch = null;
                        if (needRomanize
                                && !isBlank(enhancement.romanized)
                                && !enhancement.romanized.equals(line.text)
                                && !SpicyTextDetection.hasRomanizableScript(enhancement.romanized)) {
                            linePatch = new LyricsProcessingPatch.LinePatch(index);
                            linePatch.setRomanizedText(enhancement.romanized);
                            localChanged++;
                        }
                        if (localChanged > 0) {
                            networkPatches.add(linePatch);
                            changed.addAndGet(localChanged);
                        }
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + " secondary processing line failed: " + t);
                    } finally {
                        int processed = done.incrementAndGet();
                        if (processed % 12 == 0) {
                            handler.post(() -> {
                                if (currentGuard == null || currentGuard.isCurrent(id, generation, snapshot)) {
                                    callback.progress("Network romanization... " + processed + "/" + romanNetworkWork.size());
                                }
                            });
                        }
                        latch.countDown();
                    }
                });
            }

            try {
                latch.await();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }

            if (wantGoogleTranslate && !translationWork.isEmpty()
                    && (currentGuard == null || currentGuard.isCurrent(id, generation, snapshot))) {
                List<Integer> retryWork = translateBatchPass(
                        id, effectiveSourceLang, targetLang, workerSnapshot, translationWork,
                        networkPatches, changed, true);
                if (!retryWork.isEmpty() && (currentGuard == null || currentGuard.isCurrent(id, generation, snapshot))) {
                    handler.post(() -> {
                        if (currentGuard == null || currentGuard.isCurrent(id, generation, snapshot)) {
                            callback.progress("Retrying echoed translations... " + retryWork.size());
                        }
                    });
                    translateBatchPass(id, effectiveSourceLang, targetLang, workerSnapshot, retryWork,
                            networkPatches, changed, false);
                }
            }

            int finalChanged = changed.get();
            LyricsProcessingPatch finalPatch = flagsPatch(false, false, false,
                    true, true, finalChanged);
            for (LyricsProcessingPatch.LinePatch patch : networkPatches) finalPatch.addLinePatch(patch);
            handler.post(() -> {
                if (currentGuard == null || currentGuard.isCurrent(id, generation, snapshot)) {
                    finalPatch.applyTo(snapshot);
                    callback.complete("Enhanced " + finalChanged + " lyric fields", finalChanged);
                }
            });
        });
    }

    private List<Integer> translateBatchPass(
            String id,
            String sourceLang,
            String targetLang,
            LyricsDocument workerSnapshot,
            List<Integer> work,
            List<LyricsProcessingPatch.LinePatch> networkPatches,
            AtomicInteger changed,
            boolean collectRetry
    ) {
        List<Integer> retry = new ArrayList<>();
        if (workerSnapshot == null || workerSnapshot.lines == null || work == null || work.isEmpty()) return retry;
        List<List<GoogleEnhancer.BatchLine>> batches = translationBatches(workerSnapshot, work);
        for (List<GoogleEnhancer.BatchLine> batch : batches) {
            if (batch == null || batch.isEmpty()) continue;
            GoogleEnhancer.BatchResult translated = GoogleEnhancer.translateBatch(
                    context, http, processingVersion, id, sourceLang, targetLang, batch);
            for (GoogleEnhancer.BatchLine item : batch) {
                if (item == null || item.index < 0 || item.index >= workerSnapshot.lines.size()) continue;
                LyricsLine line = workerSnapshot.lines.get(item.index);
                if (line == null) continue;
                String value = translated.translations.get(item.index);
                boolean fromCache = translated.cachedIndices.contains(item.index);
                if (isBlank(value) || GoogleEnhancer.sameText(value, line.text)) {
                    if (collectRetry && !fromCache) retry.add(item.index);
                    continue;
                }
                LyricsProcessingPatch.LinePatch linePatch = new LyricsProcessingPatch.LinePatch(item.index);
                linePatch.setTranslatedText(value);
                networkPatches.add(linePatch);
                changed.incrementAndGet();
            }
        }
        return retry;
    }

    private List<List<GoogleEnhancer.BatchLine>> translationBatches(LyricsDocument doc, List<Integer> work) {
        List<List<GoogleEnhancer.BatchLine>> batches = new ArrayList<>();
        List<GoogleEnhancer.BatchLine> current = new ArrayList<>();
        int currentChars = 0;
        for (int index : work) {
            if (doc == null || doc.lines == null || index < 0 || index >= doc.lines.size()) continue;
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
        return batches;
    }

    public void reprocessLocal(
            LyricsDocument snapshot,
            boolean showRomanization,
            RomanizationOptions opts,
            String reason,
            CurrentGuard currentGuard,
            LocalCallback callback
    ) {
        if (snapshot == null || snapshot.lines == null || snapshot.lines.isEmpty()) return;
        LyricsDocument workerSnapshot = LyricsDocument.copyOf(snapshot);
        if (workerSnapshot == null || workerSnapshot.lines == null || workerSnapshot.lines.isEmpty()) return;
        String fullText = LyricsDocumentProcessor.collectText(workerSnapshot);
        processor.execute(() -> {
            AtomicInteger changed = new AtomicInteger();
            LyricsProcessingPatch patch = flagsPatch(
                    workerSnapshot.romanizationPending,
                    workerSnapshot.translationPending,
                    workerSnapshot.processingPending,
                    workerSnapshot.includesRomanization,
                    workerSnapshot.includesTranslation,
                    0);
            try {
                if (showRomanization) {
                    for (int index = 0; index < workerSnapshot.lines.size(); index++) {
                        LyricsLine source = workerSnapshot.lines.get(index);
                        if (source == null || isBlank(source.text) || source.interlude) continue;
                        LyricsLine line = LyricsLine.copyOf(source);
                        String before = safe(line.romanizedText);
                        LyricsLocalRomanizer.clearSegmentRomanization(line);
                        String local = LyricsLocalRomanizer.romanizeLine(opts, workerSnapshot, line, fullText);
                        if (!isBlank(local) && !local.equals(line.text) && !SpicyTextDetection.hasRomanizableScript(local)) {
                            line.romanizedText = local;
                            if (!before.equals(local)) changed.incrementAndGet();
                        }
                        LyricsLocalRomanizer.populateLocalSegmentRomanization(opts, workerSnapshot, line, fullText);
                        patch.addLinePatch(LyricsProcessingPatch.fromLine(index, line, true, false));
                    }
                }
            } catch (Throwable t) {
                XposedBridge.log(TAG + " local mode reprocess failed: " + t);
            }
            patch.changed = changed.get();
            handler.post(() -> {
                boolean current = currentGuard == null || currentGuard.isCurrent("", 0, snapshot);
                if (current) {
                    patch.applyTo(snapshot);
                    LyricsDocumentProcessor.saveProcessedCache(context, snapshot, opts, processingVersion);
                }
                callback.complete(reason, changed.get(), current);
            });
        });
    }

    private static LyricsProcessingPatch flagsPatch(
            boolean romanizationPending,
            boolean translationPending,
            boolean processingPending,
            boolean includesRomanization,
            boolean includesTranslation,
            int changed
    ) {
        LyricsProcessingPatch patch = new LyricsProcessingPatch();
        patch.romanizationPending = romanizationPending;
        patch.translationPending = translationPending;
        patch.processingPending = processingPending;
        patch.includesRomanization = includesRomanization;
        patch.includesTranslation = includesTranslation;
        patch.changed = changed;
        return patch;
    }

    public interface CurrentGuard {
        boolean isCurrent(String id, int generation, LyricsDocument snapshot);
    }

    public interface Callback {
        void rerender(String message);
        void progress(String message);
        void complete(String message, int changed);
    }

    public interface LocalCallback {
        void complete(String reason, int changed, boolean current);
    }
}
