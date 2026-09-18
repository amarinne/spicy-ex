package com.eza.spicyex.lyrics;

import com.eza.spicyex.lyrics.session.DetectionResult;
import com.eza.spicyex.lyrics.session.DetectionEvidence;
import com.eza.spicyex.xposed.XpLog;
import org.apache.tika.langdetect.charsoup.core.CharSoupModel;
import org.apache.tika.langdetect.charsoup.core.FeatureExtractor;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** One compact model per process; script routing and durable results avoid unnecessary inference. */
final class LanguageDetectorManager {
    interface DetectorHandle {
        Analyze analyze(String text);
        void unload();
    }
    interface DetectorFactory { DetectorHandle create() throws Exception; }
    static final class Analyze {
        final String language;
        final double confidence;
        Analyze(String language, double confidence) {
            this.language = language;
            this.confidence = confidence;
        }
    }
    private static final class Holder {
        static final LanguageDetectorManager INSTANCE = new LanguageDetectorManager(CharSoupHandle::new);
    }
    static LanguageDetectorManager shared() { return Holder.INSTANCE; }

    private final DetectorFactory factory;
    private final AtomicLong detections = new AtomicLong();
    private DetectorHandle handle;
    private int active;
    private boolean retired;

    LanguageDetectorManager(DetectorFactory factory) { this.factory = factory; }

    DetectionResult detect(String text, DetectionResult known) {
        return known != null && known.isReusable() && known.sourceText.equals(text) ? known : detect(text);
    }

    DetectionResult detect(String text) {
        detections.incrementAndGet();
        ScriptClassifier.ScriptClass script = ScriptClassifier.classify(text);
        if (text == null || text.codePoints().noneMatch(Character::isLetter)) {
            return DetectionResult.unknown("", text);
        }
        switch (script) {
            case JAPANESE: return DetectionResult.detected("", text, script, "ja", 1).withEvidence(DetectionEvidence.SCRIPT);
            case KOREAN: return DetectionResult.detected("", text, script, "ko", 1).withEvidence(DetectionEvidence.SCRIPT);
            case GREEK: return DetectionResult.detected("", text, script, "el", 1).withEvidence(DetectionEvidence.SCRIPT);
            default: break;
        }
        // Very low signal has no reliable language, and must not cause repeated model work.
        if (text.codePoints().filter(Character::isLetter).count() < 2) {
            return DetectionResult.scriptOnly("", text, script);
        }
        DetectorHandle lease = null;
        try {
            lease = acquire();
            if (lease == null) return DetectionResult.error("", text, script);
            Analyze result = lease.analyze(text);
            if (result == null || result.language == null || result.language.isEmpty()) {
                return DetectionResult.scriptOnly("", text, script);
            }
            return DetectionResult.detected("", text, script, result.language, result.confidence);
        } catch (Throwable failure) {
            XpLog.log("[SpotifyPlusDetection] backend failed: " + failure.getClass().getSimpleName());
            return DetectionResult.error("", text, script);
        } finally {
            if (lease != null) release();
        }
    }

    private synchronized DetectorHandle acquire() throws Exception {
        if (handle == null) handle = factory.create();
        if (handle != null) active++;
        return handle;
    }
    private synchronized void release() {
        active--;
        if (active == 0 && retired) unload();
    }
    synchronized void trimMemory() {
        retired = true;
        if (active == 0) unload();
    }
    private void unload() {
        if (handle != null) handle.unload();
        handle = null;
        retired = false;
    }
    synchronized int residentFamilies() { return handle == null ? 0 : 1; }
    long detectionCount() { return detections.get(); }

    /**
     * Collapses CharSoup Han labels to the routing family. ISO-mapped codes pass through;
     * Cantonese/Mandarin ISO-639-3 labels with no ISO-639-1 form become {@code "zh"}.
     */
    static String normalizeHanLabel(String language) {
        if ("yue".equals(language) || "cmn".equals(language)) return "zh";
        return language;
    }

    /** CharSoup core uses no native runtime or transitive Tika dependencies. */
    private static final class CharSoupHandle implements DetectorHandle {
        private CharSoupModel model;
        private FeatureExtractor extractor;
        private final Map<String, String> iso2 = new HashMap<>();

        CharSoupHandle() throws java.io.IOException {
            long started = System.nanoTime();
            model = CharSoupModel.loadFromClasspath(
                    "/org/apache/tika/langdetect/charsoup/langdetect-20260320.bin");
            extractor = model.createExtractor();
            for (String language : Locale.getISOLanguages()) {
                iso2.put(new Locale(language).getISO3Language(), language);
            }
            XpLog.log("[SpotifyPlusDetection] CharSoup 4.0.0 loaded ms="
                    + (System.nanoTime() - started) / 1_000_000);
        }

        @Override public synchronized Analyze analyze(String text) {
            float[] probabilities = model.predict(extractor.extract(text));
            int best = 0;
            for (int i = 1; i < probabilities.length; i++) {
                if (probabilities[i] > probabilities[best]) best = i;
            }
            float runner = 0;
            for (int i = 0; i < probabilities.length; i++) {
                if (i != best) runner = Math.max(runner, probabilities[i]);
            }
            // Scores are model probabilities, not calibrated lyric accuracy. Abstain on weak or
            // tied evidence; contextual routing may resolve Han without forcing a wrong label.
            float threshold = ScriptClassifier.classify(text) == ScriptClassifier.ScriptClass.CHINESE
                    ? 0.95f : 0.80f;
            if (probabilities[best] < threshold || probabilities[best] - runner < 0.20f) return null;
            String label = model.getLabel(best);
            // Yue (Cantonese) and cmn (Mandarin) are indistinguishable from written Han alone,
            // and the per-song manual Chinese-mode switch owns pinyin-vs-jyutping. Collapse both
            // to the zh family here so routing never depends on a guess the text cannot support.
            return new Analyze(normalizeHanLabel(iso2.getOrDefault(label, label)), probabilities[best]);
        }

        @Override public synchronized void unload() {
            extractor = null;
            model = null;
        }
    }
}
