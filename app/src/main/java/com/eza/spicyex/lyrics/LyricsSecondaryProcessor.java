package com.eza.spicyex.lyrics;

import android.content.Context;
import android.os.Handler;

import java.util.ArrayList;
import java.util.List;
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

        String fullText = LyricsDocumentProcessor.collectText(snapshot);
        List<LyricsLine> localWork = new ArrayList<>();
        List<LyricsLine> networkWork = new ArrayList<>();

        if (snapshot.romanizationPending) {
            for (LyricsLine line : snapshot.lines) {
                if (line == null || isBlank(line.text) || line.interlude) continue;
                if (LyricsLocalRomanizer.shouldLocalRomanize(showRomanization, opts.chineseMode, snapshot, line, fullText)) localWork.add(line);
            }
            for (LyricsLine line : snapshot.lines) {
                if (line == null || isBlank(line.text) || line.interlude) continue;
                if (LyricsLocalRomanizer.shouldGoogleRomanize(showRomanization, line) && !networkWork.contains(line)) {
                    networkWork.add(line);
                }
            }
        }
        if (snapshot.translationPending) {
            for (LyricsLine line : snapshot.lines) {
                if (line == null || isBlank(line.text) || line.interlude) continue;
                if (LyricsLocalRomanizer.shouldGoogleTranslate(snapshot, line) && !networkWork.contains(line)) {
                    networkWork.add(line);
                }
            }
        }

        if (localWork.isEmpty() && networkWork.isEmpty()) {
            snapshot.romanizationPending = false;
            snapshot.translationPending = false;
            snapshot.processingPending = false;
            return;
        }

        final boolean wantGoogleRomanize = snapshot.romanizationPending;
        final boolean wantGoogleTranslate = snapshot.translationPending;
        processor.execute(() -> {
            AtomicInteger changed = new AtomicInteger();

            for (LyricsLine line : localWork) {
                if (currentGuard != null && !currentGuard.isCurrent(id, generation, snapshot)) return;
                String local = LyricsLocalRomanizer.romanizeLine(opts, snapshot, line, fullText);
                if (!isBlank(local) && !local.equals(line.text) && !SpicyTextDetection.hasRomanizableScript(local)) {
                    line.romanizedText = local;
                    LyricsLocalRomanizer.populateLocalSegmentRomanization(opts, snapshot, line, fullText);
                    LyricCaches.putProcessingValue(context, processingVersion,
                            LyricCaches.romanizationKey(id, snapshot.language, line.text), local);
                    changed.incrementAndGet();
                }
            }

            if (changed.get() > 0) {
                handler.post(() -> {
                    if (currentGuard == null || currentGuard.isCurrent(id, generation, snapshot)) {
                        callback.rerender("Local romanization ready");
                    }
                });
            }
            snapshot.romanizationPending = false;
            snapshot.includesRomanization = true;

            if (networkWork.isEmpty()) {
                snapshot.translationPending = false;
                snapshot.processingPending = false;
                int finalChanged = changed.get();
                handler.post(() -> {
                    if (currentGuard == null || currentGuard.isCurrent(id, generation, snapshot)) {
                        callback.complete("Enhanced " + finalChanged + " lyric fields", finalChanged);
                    }
                });
                return;
            }

            // Network translation is about to run (slow, per-line). Persist the romanization we just
            // computed so closing the screen before it finishes doesn't lose it — reopening restores
            // romanization from cache and only re-runs the remaining translation pass.
            LyricsDocumentProcessor.saveProcessedCache(context, snapshot, opts, processingVersion);

            CountDownLatch latch = new CountDownLatch(networkWork.size());
            AtomicInteger done = new AtomicInteger();
            for (LyricsLine line : networkWork) {
                googleWorkers.execute(() -> {
                    try {
                        if (currentGuard != null && !currentGuard.isCurrent(id, generation, snapshot)) return;
                        boolean needRomanize = wantGoogleRomanize && LyricsLocalRomanizer.shouldGoogleRomanize(showRomanization, line);
                        boolean needTranslate = wantGoogleTranslate && LyricsLocalRomanizer.shouldGoogleTranslate(snapshot, line);
                        GoogleEnhancer.Enhancement enhancement = GoogleEnhancer.enhanceLine(context, http,
                                processingVersion, id, effectiveSourceLang, targetLang, line.text,
                                needRomanize, needTranslate);
                        int localChanged = 0;
                        if (needRomanize
                                && !isBlank(enhancement.romanized)
                                && !enhancement.romanized.equals(line.text)
                                && !SpicyTextDetection.hasRomanizableScript(enhancement.romanized)) {
                            line.romanizedText = enhancement.romanized;
                            localChanged++;
                        }
                        if (needTranslate && !isBlank(enhancement.translated) && !enhancement.translated.equals(line.text)) {
                            line.translatedText = enhancement.translated;
                            localChanged++;
                        }
                        if (localChanged > 0) changed.addAndGet(localChanged);
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + " secondary processing line failed: " + t);
                    } finally {
                        int processed = done.incrementAndGet();
                        if (processed % 12 == 0) {
                            handler.post(() -> {
                                if (currentGuard == null || currentGuard.isCurrent(id, generation, snapshot)) {
                                    callback.progress("Network translation... " + processed + "/" + networkWork.size());
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

            snapshot.translationPending = false;
            snapshot.includesTranslation = true;
            snapshot.processingPending = false;
            int finalChanged = changed.get();
            handler.post(() -> {
                if (currentGuard == null || currentGuard.isCurrent(id, generation, snapshot)) {
                    callback.complete("Enhanced " + finalChanged + " lyric fields", finalChanged);
                }
            });
        });
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
        String fullText = LyricsDocumentProcessor.collectText(snapshot);
        processor.execute(() -> {
            AtomicInteger changed = new AtomicInteger();
            try {
                if (showRomanization) {
                    for (LyricsLine line : snapshot.lines) {
                        if (line == null || isBlank(line.text) || line.interlude) continue;
                        String before = safe(line.romanizedText);
                        LyricsLocalRomanizer.clearSegmentRomanization(line);
                        String local = LyricsLocalRomanizer.romanizeLine(opts, snapshot, line, fullText);
                        if (!isBlank(local) && !local.equals(line.text) && !SpicyTextDetection.hasRomanizableScript(local)) {
                            line.romanizedText = local;
                            if (!before.equals(local)) changed.incrementAndGet();
                        }
                        LyricsLocalRomanizer.populateLocalSegmentRomanization(opts, snapshot, line, fullText);
                    }
                }
                LyricsDocumentProcessor.saveProcessedCache(context, snapshot, opts, processingVersion);
            } catch (Throwable t) {
                XposedBridge.log(TAG + " local mode reprocess failed: " + t);
            }
            handler.post(() -> {
                boolean current = currentGuard == null || currentGuard.isCurrent("", 0, snapshot);
                callback.complete(reason, changed.get(), current);
            });
        });
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
