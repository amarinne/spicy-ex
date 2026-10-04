package com.eza.spicyex.lyrics.processing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.eza.spicyex.Settings;
import com.eza.spicyex.SettingsStore;
import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.lyrics.LyricsLine;
import com.eza.spicyex.lyrics.ai.AiCredentialStore;
import com.eza.spicyex.lyrics.ai.AiHttpTestControl;
import com.eza.spicyex.lyrics.ai.AiRecordStores;
import com.eza.spicyex.lyrics.ai.AiSettings;
import com.eza.spicyex.lyrics.ai.FakeAiRecordStore;
import com.eza.spicyex.lyrics.language.KoreanDisplayMode;
import com.eza.spicyex.lyrics.language.RomanizationOptions;
import com.eza.spicyex.lyrics.language.ScriptClassifier;
import com.eza.spicyex.lyrics.session.DerivedLayerArtifact;
import com.eza.spicyex.lyrics.session.DetectionResult;
import com.eza.spicyex.lyrics.session.LayerFailure;
import com.eza.spicyex.lyrics.session.LayerKind;
import com.eza.spicyex.lyrics.session.SoundArtifact;
import com.eza.spicyex.testsupport.FakeAndroidContext;

import android.content.Context;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.OkHttpClient;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * A language set to Off means no reading from anyone: not the local rule engine, not the
 * Google fallback, and not the AI gap filler. The Sound lane's network tiers are mode-blind
 * by construction (they only see scripts), so the lane withholds Off rows from the work
 * lists and the AI gap decision instead.
 */
public class LyricsSoundLaneOffModesTest {
    private static final long WAIT_MS = 5_000L;

    private FakeAndroidContext context;
    private AiSettings aiSettings;
    private FakeAiRecordStore recordStore;
    private ExecutorService laneExecutor;
    private ExecutorService networkWorkers;
    private ExecutorService aiExecutor;
    private OkHttpClient http;

    @Before public void setUp() throws Exception {
        context = new FakeAndroidContext();
        SettingsStore settingsStore = new SettingsStore(context);
        aiSettings = new AiSettings(settingsStore,
                new AiCredentialStore(context, new IdentityCipher()));
        recordStore = new FakeAiRecordStore();
        laneExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "sound-off"));
        networkWorkers = Executors.newSingleThreadExecutor(r -> new Thread(r, "sound-off-net"));
        aiExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "sound-off-ai"));
        http = new OkHttpClient();
        AiRecordStores.installFactoryForTest((Context ignored) -> recordStore);
        AiHttpTestControl.install(401);
        settingsStore.put(Settings.AI_ENABLED, true);
        settingsStore.put(Settings.TRANSLITERATION_ENABLED, true);
        settingsStore.put(Settings.AI_PROVIDER, AiSettings.PROVIDER_OPENAI);
        settingsStore.put(Settings.AI_MODEL_OPENAI, "gpt-4o-mini");
        assertTrue(new AiCredentialStore(context, new IdentityCipher())
                .save(aiSettings.credentialScope(), "sk-test-key"));
    }

    @After public void tearDown() throws Exception {
        laneExecutor.shutdownNow();
        networkWorkers.shutdownNow();
        aiExecutor.shutdownNow();
        AiRecordStores.installFactoryForTest(null);
        AiHttpTestControl.restore();
    }

    @Test public void koreanOffWithholdsKoreanRowsFromEverySoundTier() throws Exception {
        LyricsDocument document = document("ko", null, "안녕하세요", "사랑해요");
        RomanizationOptions off = new RomanizationOptions(
                RomanizationOptions.DEFAULTS.chineseMode, KoreanDisplayMode.OFF.value,
                false, RomanizationOptions.DEFAULTS.cyrillicMode, false);
        ArtifactCallback callback = new ArtifactCallback();

        assertTrue(newLane().start("korean-off", 1, document, true, off, null, "ko",
                true, (id, generation, snapshot) -> true, callback));

        SoundArtifact artifact = callback.await();
        assertNull("Off rows produce no Sound artifact at all, local or otherwise", artifact);
        assertEquals("Off rows are not AI gaps: nothing to bill", 0, AiHttpTestControl.count());
        assertTrue("no paid identity is minted for an Off song", recordStore.keys().isEmpty());
    }

    @Test public void cyrillicOffWithholdsCyrillicRowsFromEverySoundTier() throws Exception {
        LyricsDocument document = document("ru", null, "Моя любовь", "Я тебя люблю");
        RomanizationOptions off = new RomanizationOptions(
                RomanizationOptions.DEFAULTS.chineseMode,
                RomanizationOptions.DEFAULTS.koreanMode,
                false, "Off", false);
        ArtifactCallback callback = new ArtifactCallback();

        assertTrue(newLane().start("cyrillic-off", 1, document, true, off, null, "ru",
                true, (id, generation, snapshot) -> true, callback));

        SoundArtifact artifact = callback.await();
        assertNull("Off rows produce no Sound artifact at all, local or otherwise", artifact);
        assertEquals("Off rows are not AI gaps: nothing to bill", 0, AiHttpTestControl.count());
        assertTrue("no paid identity is minted for an Off song", recordStore.keys().isEmpty());
    }

    @Test public void chineseOffWithholdsChineseRowsFromEverySoundTier() throws Exception {
        LyricsDocument document = document("zh", "zh", "你好", "谢谢");
        RomanizationOptions off = new RomanizationOptions(
                "", RomanizationOptions.DEFAULTS.koreanMode,
                false, RomanizationOptions.DEFAULTS.cyrillicMode, false);
        ArtifactCallback callback = new ArtifactCallback();

        assertTrue(newLane().start("chinese-off", 1, document, true, off, null, "zh",
                true, (id, generation, snapshot) -> true, callback));

        SoundArtifact artifact = callback.await();
        assertNull("Off rows produce no Sound artifact at all, local or otherwise", artifact);
        assertEquals("Off rows are not AI gaps: nothing to bill", 0, AiHttpTestControl.count());
        assertTrue("no paid identity is minted for an Off song", recordStore.keys().isEmpty());
    }

    @Test public void onModesStillReadLocallyWithoutAiBilling() throws Exception {
        assertCoveredLocally(document("ko", null, "안녕하세요"), "ko");
        assertCoveredLocally(document("ru", null, "Моя любовь"), "ru");
        assertCoveredLocally(document("zh", "zh", "你好"), "zh");
    }

    private void assertCoveredLocally(LyricsDocument document, String sourceLang)
            throws Exception {
        AiHttpTestControl.clear();
        ArtifactCallback callback = new ArtifactCallback();

        assertTrue(newLane().start("on-" + sourceLang, 1, document, true,
                RomanizationOptions.DEFAULTS, null, sourceLang,
                false, (id, generation, snapshot) -> true, callback));

        SoundArtifact artifact = callback.await();
        assertTrue("the local engine covers its own script: " + sourceLang,
                artifact != null && !artifact.isEmpty());
        assertEquals("covered rows are not AI gaps: nothing to bill",
                0, AiHttpTestControl.count());
    }

    private LyricsSoundLane newLane() {
        return new LyricsSoundLane(context, http, laneExecutor, networkWorkers,
                aiExecutor, 1, Runnable::run, () -> 1_000L, ctx -> aiSettings);
    }

    /**
     * @param language document language hint
     * @param detectionLanguage session detection to attach to every line, or null for none.
     *                          Chinese rows need detected zh: without it they are unresolved
     *                          Han, which no tier sends even before this fix.
     */
    private static LyricsDocument document(String language, String detectionLanguage,
                                           String... texts) {
        LyricsDocument document = new LyricsDocument();
        document.trackId = "off-modes";
        document.language = language;
        document.romanizationPending = true;
        for (int i = 0; i < texts.length; i++) {
            LyricsLine line = new LyricsLine();
            line.text = texts[i];
            line.startMs = i * 1_000L;
            line.endMs = i * 1_000L + 900L;
            if (detectionLanguage != null) {
                line.detection = DetectionResult.detected("r" + i, line.text,
                        ScriptClassifier.ScriptClass.CHINESE, detectionLanguage, 1.0);
            }
            document.lines.add(line);
        }
        return document;
    }

    private static final class ArtifactCallback implements LyricsSecondaryProcessor.Callback {
        private final AtomicReference<SoundArtifact> artifact = new AtomicReference<>();
        private final CountDownLatch settled = new CountDownLatch(1);
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
