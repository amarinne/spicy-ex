package com.eza.spicyex.lyrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import com.eza.spicyex.Settings;
import com.eza.spicyex.SettingsStore;
import com.eza.spicyex.lyrics.ai.AiContract;
import com.eza.spicyex.lyrics.ai.AiCredentialStore;
import com.eza.spicyex.lyrics.ai.AiHttpTestControl;
import com.eza.spicyex.lyrics.ai.AiRecordStores;
import com.eza.spicyex.lyrics.ai.AiSettings;
import com.eza.spicyex.lyrics.ai.AiSignal;
import com.eza.spicyex.lyrics.ai.AiSoundRun;
import com.eza.spicyex.lyrics.ai.FakeAiRecordStore;
import com.eza.spicyex.lyrics.session.CanonicalBase;
import com.eza.spicyex.lyrics.session.CanonicalRow;
import com.eza.spicyex.lyrics.session.DerivedLayerArtifact;
import com.eza.spicyex.lyrics.session.LayerAuthority;
import com.eza.spicyex.lyrics.session.LayerFailure;
import com.eza.spicyex.lyrics.session.LayerKind;
import com.eza.spicyex.lyrics.session.LayerProvenance;
import com.eza.spicyex.lyrics.session.SoundArtifact;
import com.eza.spicyex.lyrics.session.SoundEntry;
import com.eza.spicyex.testsupport.FakeAndroidContext;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * The Sound lane's paid-reuse contract, driven on the JVM.
 *
 * <p>When the engines have nothing left to read, the only baseline the model gets is the one on
 * screen. Sound folds that baseline into the paid record's identity, so a different baseline is a
 * different question and a different bill; this asserts the identity the lane actually asks under.
 */
public final class LyricsSoundLaneBehaviorTest {

    private static final long WAIT_MS = 5_000L;

    private FakeAndroidContext context;
    private SettingsStore settingsStore;
    private AiCredentialStore credentials;
    private AiSettings aiSettings;
    private FakeAiRecordStore recordStore;
    private ExecutorService laneExecutor;
    private ExecutorService networkWorkers;
    private ExecutorService aiExecutor;
    private List<String> callbacks;

    @Before public void setUp() throws Exception {
        context = new FakeAndroidContext();
        settingsStore = new SettingsStore(context);
        credentials = new AiCredentialStore(context, new IdentityCipher());
        aiSettings = new AiSettings(settingsStore, credentials);
        recordStore = new FakeAiRecordStore();
        laneExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "lane-sound"));
        networkWorkers = Executors.newSingleThreadExecutor(r -> new Thread(r, "sound-network"));
        aiExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "sound-ai"));
        callbacks = new CopyOnWriteArrayList<>();
        AiRecordStores.installFactoryForTest((Context ignored) -> recordStore);
        AiHttpTestControl.install(401);
        settingsStore.put(Settings.AI_ENABLED, true);
        settingsStore.put(Settings.TRANSLITERATION_ENABLED, true);
        settingsStore.put(Settings.AI_PROVIDER, AiSettings.PROVIDER_OPENAI);
        settingsStore.put(Settings.AI_MODEL_OPENAI, "gpt-4o-mini");
        assertTrue(credentials.save(aiSettings.credentialScope(), "sk-test-key"));
    }

    @After public void tearDown() throws Exception {
        laneExecutor.shutdownNow();
        networkWorkers.shutdownNow();
        aiExecutor.shutdownNow();
        AiRecordStores.installFactoryForTest(null);
        AiHttpTestControl.restore();
    }

    @Test public void noWorkRunKeysThePaidAnswerToTheDisplayedBaseline() throws Exception {
        LyricsDocument document = document();
        SoundArtifact displayed = displayedSound(document);

        assertTrue(newLane().start("track", 1, document, false, RomanizationOptions.DEFAULTS,
                displayed, "ko", true, (id, generation, snapshot) -> true,
                new RecordingCallback()));

        assertTrue("expected the gap fill to settle: " + callbacks,
                await("complete"));

        Set<String> laneIdentity = recordStore.keys();
        assertEquals("one paid identity for the no-work run", 1, laneIdentity.size());
        assertEquals("the request must be the displayed baseline's question",
                referenceIdentity(document, displayed), laneIdentity);
        assertNotEquals("a baseline-less question is a different bill",
                referenceIdentity(document, null), laneIdentity);
        assertTrue("the gap fill asked the provider", AiHttpTestControl.count() > 0);
    }

    // --- Harness ----------------------------------------------------------

    private Set<String> referenceIdentity(LyricsDocument document, SoundArtifact baseline) {
        FakeAiRecordStore reference = new FakeAiRecordStore();
        AiRecordStores.installFactoryForTest((Context ignored) -> reference);
        AiSoundRun.run(context, aiSettings, CanonicalBase.fromDocument("", document), document,
                baseline, AiContract.ORTHOGRAPHY_LATIN, aiSettings.soundUsesBaseline(), true,
                new AiSignal(), null);
        AiRecordStores.installFactoryForTest((Context ignored) -> recordStore);
        return reference.keys();
    }

    private LyricsSoundLane newLane() {
        return new LyricsSoundLane(context, new okhttp3.OkHttpClient(), laneExecutor,
                networkWorkers, aiExecutor, 1, Runnable::run, () -> 1_000L,
                context -> aiSettings);
    }

    private boolean await(String callback) {
        long deadline = System.currentTimeMillis() + WAIT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (callbacks.contains(callback)) return true;
            try {
                Thread.sleep(10L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return callbacks.contains(callback);
    }

    private static LyricsDocument document() {
        LyricsDocument document = new LyricsDocument();
        document.trackId = "sound-behavior";
        document.language = "ko";
        for (int i = 0; i < 2; i++) {
            LyricsLine line = new LyricsLine();
            line.text = i == 0 ? "안녕하세요" : "반갑습니다";
            line.startMs = i * 1_000L;
            line.endMs = i * 1_000L + 900L;
            document.lines.add(line);
        }
        return document;
    }

    /** What the engines already got right: the first line, leaving the second as a gap. */
    private static SoundArtifact displayedSound(LyricsDocument document) {
        List<CanonicalRow> rows = CanonicalBase.fromDocument("", document).rows;
        List<SoundEntry> entries = new ArrayList<>();
        entries.add(new SoundEntry(rows.get(0).rowId, "annyeonghaseyo", "Local", null,
                Collections.<SoundEntry.SpanReading>emptyList()));
        return new SoundArtifact(document.trackId, "sound-config",
                new LayerProvenance(LayerAuthority.DETERMINISTIC, "local", "sound-local", "local",
                        1L),
                entries, false);
    }

    private final class RecordingCallback implements LyricsSecondaryProcessor.Callback {
        @Override public void rerender(LayerKind layer, DerivedLayerArtifact partial,
                                       String message) {
            callbacks.add("rerender");
        }

        @Override public void progress(String message) {
            callbacks.add("progress");
        }

        @Override public void complete(LayerKind layer, DerivedLayerArtifact artifact,
                                       LayerFailure failure, String message, int changed) {
            callbacks.add("complete");
        }
    }

    /** The credential store with a cipher that is happy to live on a plain JVM. */
    static final class IdentityCipher implements AiCredentialStore.Cipher {
        @Override public String encrypt(String plaintext) {
            return plaintext;
        }

        @Override public String decrypt(String ciphertext) {
            return ciphertext;
        }

        @Override public void clear() {
        }
    }
}
