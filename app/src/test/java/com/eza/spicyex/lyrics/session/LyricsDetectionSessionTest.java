package com.eza.spicyex.lyrics.session;

import android.content.Context;

import com.eza.spicyex.lyrics.ScriptClassifier;

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class LyricsDetectionSessionTest {
    private static final ScriptClassifier.ScriptClass LATIN = ScriptClassifier.ScriptClass.LATIN;

    @Test
    public void restoresCachedRowsAndDetectsOnlyMissingOnes() {
        FakeCache cache = new FakeCache();
        CountingDetector detector = new CountingDetector();
        LyricsDetectionSession session = newSession(detector, cache);
        CanonicalBase base = base("hello world", "bonjour monde");
        cache.stored = new DetectionArtifact(base.digest, DetectionArtifact.DETECTOR_POLICY_ID,
                Collections.singletonList(DetectionResult.detected(
                        base.rows.get(0).rowId, "hello world", LATIN, "en", 0.9)), true);

        CapturingCallback callback = new CapturingCallback();
        session.start(base, 1, (candidate, generation) -> true, callback);

        assertEquals(1, detector.calls);
        assertNotNull(callback.artifact);
        assertEquals(2, callback.artifact.size());
        assertEquals("en", callback.artifact.result(base.rows.get(0).rowId).language);
        assertEquals(1, cache.saves);
        assertEquals("de", callback.artifact.result(base.rows.get(1).rowId).language);
    }

    @Test
    public void changedRowTextIsNotReused() {
        FakeCache cache = new FakeCache();
        CountingDetector detector = new CountingDetector();
        LyricsDetectionSession session = newSession(detector, cache);
        CanonicalBase base = base("hello world");
        // Same row ID, stale text: the session must not trust the cached row.
        cache.stored = new DetectionArtifact(base.digest, DetectionArtifact.DETECTOR_POLICY_ID,
                Collections.singletonList(DetectionResult.detected(
                        base.rows.get(0).rowId, "other text", LATIN, "en", 0.9)), false);

        CapturingCallback callback = new CapturingCallback();
        session.start(base, 1, (candidate, generation) -> true, callback);

        assertEquals(1, detector.calls);
        assertEquals("de", callback.artifact.result(base.rows.get(0).rowId).language);
    }

    @Test
    public void staleGenerationIsRejectedBeforeCallback() {
        FakeCache cache = new FakeCache();
        CountingDetector detector = new CountingDetector();
        LyricsDetectionSession session = newSession(detector, cache);
        CanonicalBase base = base("hello world");

        CapturingCallback callback = new CapturingCallback();
        session.start(base, 1, (candidate, generation) -> false, callback);

        assertEquals(0, callback.completions);
    }

    @Test
    public void cancelledCompletionCannotConsumeReplacementRunForSameDigest() {
        ManualExecutor work = new ManualExecutor();
        ManualExecutor deliveries = new ManualExecutor();
        LyricsDetectionSession session = new LyricsDetectionSession(
                null, work, deliveries::execute, new FakeCache(), new CountingDetector());
        CanonicalBase base = base("hello world");
        CapturingCallback old = new CapturingCallback();
        CapturingCallback replacement = new CapturingCallback();
        int[] currentGeneration = {1};

        session.start(base, 1, (candidate, generation) -> generation == currentGeneration[0], old);
        work.drain(); // Completion is queued on the main thread when the track changes.
        session.cancelActive();
        currentGeneration[0] = 2;
        session.start(base, 2, (candidate, generation) -> generation == currentGeneration[0], replacement);

        deliveries.drain();
        assertEquals(0, old.completions);
        assertEquals(0, replacement.completions);
        assertEquals(0, session.memoryEntryCount());

        work.drain();
        deliveries.drain();
        assertEquals(0, old.completions);
        assertEquals(1, replacement.completions);
        assertNotNull(replacement.artifact);
        assertEquals(1, session.memoryEntryCount());
    }

    @Test
    public void concurrentRequestsForSameDigestCoalesceOntoOneRun() {
        ManualExecutor executor = new ManualExecutor();
        FakeCache cache = new FakeCache();
        CountingDetector detector = new CountingDetector();
        LyricsDetectionSession session = new LyricsDetectionSession(
                null, executor, Runnable::run, cache, detector);
        CanonicalBase base = base("hello world", "bonjour monde");

        CapturingCallback first = new CapturingCallback();
        CapturingCallback second = new CapturingCallback();
        session.start(base, 1, (candidate, generation) -> true, first);
        session.start(base, 1, (candidate, generation) -> true, second);

        assertEquals(1, executor.pending());
        executor.drain();

        assertEquals(2, detector.calls);
        assertEquals(1, first.completions);
        assertEquals(1, second.completions);
    }

    @Test
    public void unknownResultsAreTerminalAndNotDetectedAgain() {
        FakeCache cache = new FakeCache();
        TerminalDetector detector = new TerminalDetector();
        LyricsDetectionSession session = newSession(detector, cache);
        CanonicalBase base = base("♪ ♪");

        CapturingCallback first = new CapturingCallback();
        session.start(base, 1, (candidate, generation) -> true, first);
        assertEquals(1, detector.calls);
        assertEquals(DetectionStatus.UNKNOWN,
                first.artifact.result(base.rows.get(0).rowId).status);
        assertFalse(first.artifact.partial);

        session.trimMemory();
        CapturingCallback second = new CapturingCallback();
        session.start(base, 1, (candidate, generation) -> true, second);

        assertEquals(1, detector.calls);
        assertEquals(DetectionStatus.UNKNOWN,
                second.artifact.result(base.rows.get(0).rowId).status);
    }

    @Test
    public void errorResultsAreRetriedOnTheNextLoad() {
        FakeCache cache = new FakeCache();
        FlakyDetector detector = new FlakyDetector();
        LyricsDetectionSession session = newSession(detector, cache);
        CanonicalBase base = base("hello world");

        CapturingCallback first = new CapturingCallback();
        session.start(base, 1, (candidate, generation) -> true, first);
        assertEquals(DetectionStatus.ERROR, first.artifact.result(base.rows.get(0).rowId).status);
        assertTrue(first.artifact.partial);

        session.trimMemory();
        CapturingCallback second = new CapturingCallback();
        session.start(base, 1, (candidate, generation) -> true, second);

        assertEquals(2, detector.calls);
        assertEquals("de", second.artifact.result(base.rows.get(0).rowId).language);
        assertFalse(second.artifact.partial);
    }

    @Test
    public void auxiliaryProviderTextsAreDetectedPersistedAndReused() {
        FakeCache cache = new FakeCache();
        CountingDetector detector = new CountingDetector();
        LyricsDetectionSession session = newSession(detector, cache);
        CanonicalBase base = base("hello world");
        List<String> auxiliary = Collections.singletonList("Hola amigo");

        CapturingCallback first = new CapturingCallback();
        session.start(base, auxiliary, 1, (candidate, generation) -> true, first);
        assertEquals(2, detector.calls);
        assertEquals(1, cache.textSaves);
        assertEquals(1, cache.texts.size());

        session.trimMemory();
        CapturingCallback second = new CapturingCallback();
        session.start(base, auxiliary, 1, (candidate, generation) -> true, second);

        assertEquals(2, detector.calls);
        assertEquals(1, cache.textSaves);
        assertNotNull(first.artifact);
        assertNotNull(second.artifact);
    }

    @Test
    public void cachedCanonicalDetectionStillDetectsNewProviderTexts() {
        FakeCache cache = new FakeCache();
        CountingDetector detector = new CountingDetector();
        LyricsDetectionSession session = newSession(detector, cache);
        CanonicalBase base = base("hello world");

        CapturingCallback first = new CapturingCallback();
        session.start(base, 1, (candidate, generation) -> true, first);
        assertEquals(1, detector.calls);
        assertEquals(0, cache.textSaves);

        // The same digest arrives with a provider translation added since the last run; the
        // cached canonical artifact must not short-circuit detection of the new text.
        CapturingCallback second = new CapturingCallback();
        session.start(base, Collections.singletonList("Hola amigo"), 1,
                (candidate, generation) -> true, second);

        assertEquals(2, detector.calls);
        assertEquals(1, cache.textSaves);
        assertNotNull(second.artifact);
    }

    @Test
    public void trimMemoryDropsInMemoryArtifactAndReloadsFromCache() {
        FakeCache cache = new FakeCache();
        CountingDetector detector = new CountingDetector();
        LyricsDetectionSession session = newSession(detector, cache);
        CanonicalBase base = base("hello world");

        session.start(base, 1, (candidate, generation) -> true, new CapturingCallback());
        assertEquals(1, session.memoryEntryCount());
        session.trimMemory();
        assertEquals(0, session.memoryEntryCount());

        session.start(base, 1, (candidate, generation) -> true, new CapturingCallback());
        assertEquals(2, cache.restores);
        assertEquals(2, cache.saves);
        assertEquals(1, detector.calls);
    }

    private static LyricsDetectionSession newSession(LyricsDetectionSession.Detector detector,
                                                     LyricsDetectionSession.Cache cache) {
        return new LyricsDetectionSession(null, Runnable::run, Runnable::run, cache, detector);
    }

    private static CanonicalBase base(String... texts) {
        List<CanonicalRow> rows = new ArrayList<>();
        for (int i = 0; i < texts.length; i++) {
            rows.add(new CanonicalRow(i, texts[i], i * 1000L, (i + 1) * 1000L, false,
                    Collections.emptyList()));
        }
        return new CanonicalBase("spotify:track:x", "x", "en", "provider", "source", "Line",
                texts.length * 1000L, rows);
    }

    private static final class CountingDetector implements LyricsDetectionSession.Detector {
        int calls;

        @Override
        public DetectionResult detect(String text, DetectionResult known) {
            calls++;
            return DetectionResult.detected("", text, LATIN, "de", 0.5);
        }
    }

    private static final class TerminalDetector implements LyricsDetectionSession.Detector {
        int calls;

        @Override
        public DetectionResult detect(String text, DetectionResult known) {
            calls++;
            return DetectionResult.unknown("", text);
        }
    }

    private static final class FlakyDetector implements LyricsDetectionSession.Detector {
        int calls;

        @Override
        public DetectionResult detect(String text, DetectionResult known) {
            calls++;
            if (calls == 1) return DetectionResult.error("", text, LATIN);
            return DetectionResult.detected("", text, LATIN, "de", 0.5);
        }
    }

    private static final class FakeCache implements LyricsDetectionSession.Cache {
        DetectionArtifact stored;
        int restores;
        int saves;
        int textSaves;
        final java.util.Map<String, DetectionResult> texts = new java.util.HashMap<>();

        @Override
        public DetectionArtifact restore(Context context, CanonicalBase base) {
            restores++;
            return stored;
        }

        @Override
        public boolean save(Context context, CanonicalBase base, DetectionArtifact artifact) {
            saves++;
            stored = artifact;
            return true;
        }

        @Override
        public DetectionResult restoreText(Context context, String text) {
            return texts.get(text);
        }

        @Override
        public boolean saveText(Context context, String text, DetectionResult result) {
            textSaves++;
            texts.put(text, result);
            return true;
        }
    }

    private static final class CapturingCallback implements LyricsDetectionSession.Callback {
        int completions;
        DetectionArtifact artifact;

        @Override
        public void onDetectionReady(CanonicalBase base, DetectionArtifact artifact) {
            completions++;
            this.artifact = artifact;
        }
    }

    private static final class ManualExecutor implements Executor {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        int pending() {
            return tasks.size();
        }

        void drain() {
            while (!tasks.isEmpty()) tasks.poll().run();
        }
    }
}
