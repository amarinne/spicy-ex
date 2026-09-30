package com.eza.spicyex.lyrics.processing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.lyrics.LyricsLine;
import com.eza.spicyex.lyrics.ai.AiSettings;
import com.eza.spicyex.lyrics.language.RomanizationOptions;
import com.eza.spicyex.lyrics.session.CanonicalBase;
import com.eza.spicyex.lyrics.session.CanonicalRow;
import com.eza.spicyex.lyrics.session.DerivedLayerArtifact;
import com.eza.spicyex.lyrics.session.LayerFailure;
import com.eza.spicyex.lyrics.session.LayerKind;
import com.eza.spicyex.lyrics.session.SoundArtifact;
import com.eza.spicyex.testsupport.FakeAndroidContext;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.OkHttpClient;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * F7: a mixed successful/failed batch must persist as partial, never as complete. The network
 * transport throws here (no device, no provider), so the network-only row fails exactly like
 * a timeout while the locally read row succeeds.
 */
public class LyricsSoundLanePartialTest {
    private static final long WAIT_MS = 5_000L;

    private FakeAndroidContext context;
    private AiSettings aiSettings;
    private ExecutorService laneExecutor;
    private ExecutorService networkWorkers;
    private ExecutorService aiExecutor;
    private OkHttpClient failingHttp;

    @Before public void setUp() {
        context = new FakeAndroidContext();
        com.eza.spicyex.SettingsStore settingsStore =
                new com.eza.spicyex.SettingsStore(context);
        aiSettings = new AiSettings(settingsStore,
                new com.eza.spicyex.lyrics.ai.AiCredentialStore(context,
                        new com.eza.spicyex.lyrics.ai.AiCredentialStore.Cipher() {
                            @Override public String encrypt(String plaintext) {
                                return plaintext;
                            }

                            @Override public String decrypt(String ciphertext) {
                                return ciphertext;
                            }

                            @Override public void clear() {
                            }
                        }));
        laneExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "sound-partial"));
        networkWorkers = Executors.newSingleThreadExecutor(r -> new Thread(r, "sound-net"));
        aiExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "sound-ai"));
        failingHttp = new OkHttpClient.Builder()
                .addInterceptor(chain -> {
                    throw new java.io.IOException("F7 simulated provider timeout");
                })
                .build();
    }

    @After public void tearDown() {
        laneExecutor.shutdownNow();
        networkWorkers.shutdownNow();
        aiExecutor.shutdownNow();
    }

    @Test public void failedNetworkRowPersistsAsPartial() throws Exception {
        LyricsDocument document = document("안녕하세요", "สวัสดี");
        List<CanonicalRow> rows = CanonicalBase.fromDocument("", document).rows;
        assertEquals(2, rows.size());
        ArtifactCallback callback = new ArtifactCallback();

        assertTrue(newLane().start("f7-mixed", 1, document, true, RomanizationOptions.DEFAULTS,
                null, "ko", false, (id, generation, snapshot) -> true, callback));

        SoundArtifact artifact = callback.await();
        assertNotNull("a mixed batch still completes with what it covered", artifact);
        assertTrue("the failed network row must not persist as complete", artifact.partial);
        assertNotNull(artifact.sound(rows.get(0).rowId));
        assertNull(artifact.sound(rows.get(1).rowId));
    }

    @Test public void fullyCoveredRowsPersistAsComplete() throws Exception {
        LyricsDocument document = document("안녕하세요");
        List<CanonicalRow> rows = CanonicalBase.fromDocument("", document).rows;
        assertEquals(1, rows.size());
        ArtifactCallback callback = new ArtifactCallback();

        assertTrue(newLane().start("f7-local", 1, document, true, RomanizationOptions.DEFAULTS,
                null, "ko", false, (id, generation, snapshot) -> true, callback));

        SoundArtifact artifact = callback.await();
        assertNotNull(artifact);
        assertNotNull(artifact.sound(rows.get(0).rowId));
        assertTrue("locally covered rows stay complete: " + artifact.partial,
                !artifact.partial);
    }

    @Test public void coverageHelpersTrackRequiredVersusCovered() {
        assertTrue(LyricsSoundLane.isPartialCoverage(null, null) == false);
        assertTrue(LyricsSoundLane.isPartialCoverage(
                java.util.Collections.emptyList(), java.util.Collections.emptySet()) == false);
    }

    private LyricsSoundLane newLane() {
        return new LyricsSoundLane(context, failingHttp, laneExecutor, networkWorkers,
                aiExecutor, 1, Runnable::run, () -> 1_000L, ctx -> aiSettings);
    }

    private static LyricsDocument document(String... texts) {
        LyricsDocument document = new LyricsDocument();
        document.trackId = "f7-partial";
        document.language = "ko";
        document.romanizationPending = true;
        for (int i = 0; i < texts.length; i++) {
            LyricsLine line = new LyricsLine();
            line.text = texts[i];
            line.startMs = i * 1_000L;
            line.endMs = i * 1_000L + 900L;
            document.lines.add(line);
        }
        return document;
    }

    private static final class ArtifactCallback implements LyricsSecondaryProcessor.Callback {
        private final AtomicReference<SoundArtifact> artifact = new AtomicReference<>();
        private final java.util.concurrent.CountDownLatch settled =
                new java.util.concurrent.CountDownLatch(1);
        private final List<String> events = new CopyOnWriteArrayList<>();

        @Override public void rerender(LayerKind layer, DerivedLayerArtifact partial,
                                       String message) {
            events.add("rerender");
        }

        @Override public void progress(String message) {
            events.add("progress");
        }

        @Override public void complete(LayerKind layer, DerivedLayerArtifact completed,
                                       LayerFailure failure, String message, int changed) {
            events.add("complete");
            if (completed instanceof SoundArtifact) {
                artifact.set((SoundArtifact) completed);
            }
            settled.countDown();
        }

        SoundArtifact await() throws Exception {
            assertTrue("expected complete, saw " + events,
                    settled.await(WAIT_MS, TimeUnit.MILLISECONDS));
            return artifact.get();
        }
    }
}
