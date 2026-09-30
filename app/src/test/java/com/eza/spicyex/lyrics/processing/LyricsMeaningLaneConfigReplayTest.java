package com.eza.spicyex.lyrics.processing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.eza.spicyex.Settings;
import com.eza.spicyex.SettingsStore;
import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.lyrics.LyricsLine;
import com.eza.spicyex.lyrics.ai.AiSettings;
import com.eza.spicyex.lyrics.session.DerivedLayerArtifact;
import com.eza.spicyex.lyrics.session.LayerFailure;
import com.eza.spicyex.lyrics.session.LayerKind;
import com.eza.spicyex.lyrics.session.MeaningArtifact;
import com.eza.spicyex.testsupport.FakeAndroidContext;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import org.junit.Test;

/** Drives deferred replay through the worker and session dispatch boundaries. */
public class LyricsMeaningLaneConfigReplayTest {
    @Test public void deferredOldTargetCannotRetireOrMislabelTheNewTarget() {
        assertConfigReplay(true);
    }

    @Test public void deferredTargetChangeIsRejectedBeforeAReplacementStarts() {
        assertConfigReplay(false);
    }

    private void assertConfigReplay(boolean startReplacement) {
        FakeAndroidContext context = new FakeAndroidContext();
        SettingsStore settings = new SettingsStore(context);
        settings.put(Settings.TRANSLATION_ENABLED, true);
        settings.put(Settings.TRANSLATION_TARGET, "en");
        QueueExecutor workers = new QueueExecutor();
        Queue<Runnable> sessionDispatch = new ArrayDeque<>();
        List<String> targets = new ArrayList<>();
        int[] cancellations = {0};
        LyricsMeaningLane.MeaningProvider provider = new LyricsMeaningLane.MeaningProvider() {
            @Override public String backendId() { return "google_unofficial"; }
            @Override public boolean handles(String backend) { return true; }
            @Override public GoogleEnhancer.BatchResult translate(android.content.Context ctx,
                    OkHttpClient http, int version, String track, String source, String target,
                    List<GoogleEnhancer.BatchLine> batch, String tag) {
                targets.add(target);
                GoogleEnhancer.BatchResult result = new GoogleEnhancer.BatchResult();
                for (GoogleEnhancer.BatchLine row : batch) {
                    result.translations.put(row.index, "Translation in " + target);
                }
                return result;
            }
            @Override public void cancel(OkHttpClient http, String tag) { cancellations[0]++; }
            @Override public boolean shouldDisplay(String source, String translated) {
                return translated != null && !translated.isEmpty();
            }
        };
        LyricsMeaningLane lane = new LyricsMeaningLane(context, new OkHttpClient(), workers,
                workers, 1, provider, sessionDispatch::add, () -> 0L, AiSettings::new);
        LyricsDocument document = new LyricsDocument();
        document.trackId = "meaning-config-replay";
        document.language = "ko";
        document.translationPending = true;
        LyricsLine row = new LyricsLine();
        row.text = "안녕하세요";
        document.lines.add(row);
        Recorder old = new Recorder();
        Recorder fresh = new Recorder();
        LyricsSecondaryProcessor.CurrentGuard guard = (id, generation, snapshot) ->
                generation == 1 && snapshot == document;

        assertTrue(lane.start(document.trackId, 1, document, "google_unofficial", "en", "ko",
                "ko", false, guard, old));
        assertFalse(lane.start(document.trackId, 1, document, "google_unofficial", "en", "ko",
                "ko", false, guard, old));
        settings.put(Settings.TRANSLATION_TARGET, "fr");
        if (startReplacement) {
            assertTrue(lane.start(document.trackId, 1, document, "google_unofficial", "fr", "ko",
                    "ko", false, guard, fresh));
        }

        // Finishing the retired owner queues its replay; it must not mutate the lane here.
        workers.runNext();
        assertEquals(startReplacement ? 2 : 1, cancellations[0]);
        workers.drain();
        while (!sessionDispatch.isEmpty()) sessionDispatch.remove().run();
        workers.drain();
        while (!sessionDispatch.isEmpty()) sessionDispatch.remove().run();

        assertEquals(startReplacement ? Collections.singletonList("fr") : Collections.emptyList(), targets);
        assertEquals(0, old.completed.size());
        assertEquals(startReplacement ? 1 : 0, fresh.completed.size());
        if (startReplacement) {
            MeaningArtifact artifact = (MeaningArtifact) fresh.completed.get(0);
            assertTrue(artifact.configId.contains("|target=fr|"));
            assertEquals("Translation in fr", artifact.meaning(artifact.rowIds().iterator().next()).text);
        }
        assertEquals(startReplacement ? 2 : 1, cancellations[0]);
    }

    @Test public void soundOnlyRefreshLeavesPendingMeaningAbleToComplete() throws Exception {
        FakeAndroidContext context = new FakeAndroidContext();
        QueueExecutor workers = new QueueExecutor();
        Queue<Runnable> dispatch = new ArrayDeque<>();
        int[] cancellations = {0};
        LyricsMeaningLane.MeaningProvider provider = new LyricsMeaningLane.MeaningProvider() {
            @Override public String backendId() { return "google_unofficial"; }
            @Override public boolean handles(String backend) { return true; }
            @Override public GoogleEnhancer.BatchResult translate(android.content.Context ctx,
                    OkHttpClient http, int version, String track, String source, String target,
                    List<GoogleEnhancer.BatchLine> batch, String tag) {
                GoogleEnhancer.BatchResult result = new GoogleEnhancer.BatchResult();
                for (GoogleEnhancer.BatchLine row : batch) result.translations.put(row.index, "Hello");
                return result;
            }
            @Override public void cancel(OkHttpClient http, String tag) { cancellations[0]++; }
            @Override public boolean shouldDisplay(String source, String translated) { return translated != null; }
        };
        OkHttpClient http = new OkHttpClient();
        LyricsMeaningLane meaning = new LyricsMeaningLane(context, http, workers, workers, 1,
                provider, dispatch::add, () -> 0L, AiSettings::new);
        LyricsSoundLane sound = new LyricsSoundLane(context, http, workers, workers, workers, 1,
                dispatch::add, () -> 0L, AiSettings::new);
        LyricsSecondaryProcessor processor = new LyricsSecondaryProcessor(context, http,
                workers, workers, workers, workers, null, 1);
        for (String name : new String[]{"soundLane", "meaningLane"}) {
            java.lang.reflect.Field field = LyricsSecondaryProcessor.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(processor, name.equals("soundLane") ? sound : meaning);
        }
        LyricsDocument document = new LyricsDocument();
        document.trackId = "selected-layer";
        document.language = "ko";
        document.translationPending = true;
        LyricsLine row = new LyricsLine();
        row.text = "안녕하세요";
        document.lines.add(row);
        Recorder activeMeaning = new Recorder();
        meaning.start(document.trackId, 1, document, "google_unofficial", "en", "ko", "ko",
                false, (id, generation, snapshot) -> true, activeMeaning);
        processor.cancelLayer(LayerKind.SOUND);
        processor.start(document.trackId, 1, document, false,
                com.eza.spicyex.lyrics.language.RomanizationOptions.DEFAULTS, null,
                "google_unofficial", "en", "ko", "ko", Collections.emptySet(),
                (id, generation, snapshot) -> true, new Recorder(),
                java.util.EnumSet.of(LayerKind.SOUND));
        workers.drain();
        while (!dispatch.isEmpty()) dispatch.remove().run();
        assertEquals(0, cancellations[0]);
        assertEquals(1, activeMeaning.completed.size());
    }

    private static final class Recorder implements LyricsSecondaryProcessor.Callback {
        final List<DerivedLayerArtifact> completed = new ArrayList<>();
        @Override public void rerender(LayerKind layer, DerivedLayerArtifact partial, String message) {}
        @Override public void progress(String message) {}
        @Override public void complete(LayerKind layer, DerivedLayerArtifact artifact,
                LayerFailure failure, String message, int changed) {
            completed.add(artifact);
        }
    }

    private static final class QueueExecutor extends AbstractExecutorService {
        final Queue<Runnable> queue = new ArrayDeque<>();
        @Override public void execute(Runnable runnable) { queue.add(runnable); }
        void runNext() { queue.remove().run(); }
        void drain() { while (!queue.isEmpty()) runNext(); }
        @Override public void shutdown() {}
        @Override public List<Runnable> shutdownNow() { return Collections.emptyList(); }
        @Override public boolean isShutdown() { return false; }
        @Override public boolean isTerminated() { return false; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
    }
}
