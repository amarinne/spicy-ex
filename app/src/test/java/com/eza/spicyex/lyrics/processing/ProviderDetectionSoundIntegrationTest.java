package com.eza.spicyex.lyrics.processing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import com.eza.spicyex.Settings;
import com.eza.spicyex.SettingsStore;
import com.eza.spicyex.SpotifyTrack;
import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.lyrics.language.RomanizationOptions;
import com.eza.spicyex.lyrics.language.LyricsLocalRomanizer;
import com.eza.spicyex.lyrics.language.ScriptClassifier;
import com.eza.spicyex.lyrics.providers.LyricsParser;
import com.eza.spicyex.lyrics.session.CanonicalBase;
import com.eza.spicyex.lyrics.session.DetectionArtifact;
import com.eza.spicyex.lyrics.session.DetectionResult;
import com.eza.spicyex.lyrics.session.LyricsDetectionSession;
import com.eza.spicyex.lyrics.session.LayerFailure;
import com.eza.spicyex.lyrics.session.LayerKind;
import com.eza.spicyex.lyrics.session.DerivedLayerArtifact;
import com.eza.spicyex.lyrics.session.SoundArtifact;
import com.eza.spicyex.testsupport.FakeAndroidContext;
import com.eza.spicyex.lyrics.ai.AiCredentialStore;
import com.eza.spicyex.lyrics.ai.AiHttpTestControl;
import com.eza.spicyex.lyrics.ai.AiSettings;
import com.eza.spicyex.lyrics.ai.AiRequestLiveState;

import java.lang.reflect.Constructor;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Composition checks for the real provider/parser, detection session, local pinyin, and AI gate.
 *
 * <p>The individual helpers have their own tests. These checks intentionally cross the seams that
 * used to be tested in isolation: an Apple-shaped Spicy Org response is parsed, its canonical row
 * is detected, and that same document is handed to the Sound lane. Complete local Chinese coverage
 * must settle without a paid HTTP request.
 */
public final class ProviderDetectionSoundIntegrationTest {
    private static final String TRACK_ID = "4uGve3vqfumnGsUMtnGumr";
    private static final long WAIT_MS = 5_000L;

    private FakeAndroidContext context;
    private SettingsStore store;
    private AiSettings aiSettings;
    private ExecutorService laneExecutor;
    private ExecutorService networkWorkers;
    private ExecutorService aiExecutor;

    @Before public void setUp() throws Exception {
        context = new FakeAndroidContext();
        store = new SettingsStore(context);
        store.put(Settings.AI_ENABLED, true);
        store.put(Settings.AI_PRONUNCIATION_MODE, "Always use AI");
        store.put(Settings.AI_PRONUNCIATION_SOURCE, "Layered");
        store.put(Settings.TRANSLITERATION_ENABLED, true);
        store.put(Settings.AI_PROVIDER, AiSettings.PROVIDER_OPENAI);
        store.put(Settings.AI_MODEL_OPENAI, "gpt-4o-mini");
        aiSettings = new AiSettings(store, new AiCredentialStore(context, new IdentityCipher()));
        assertTrue(aiSettings.pronunciationAutomatic());
        AiRequestLiveState.clearForTest();
        assertTrue(aiSettings.credentials().save(aiSettings.credentialScope(), "sk-test-key"));
        laneExecutor = Executors.newSingleThreadExecutor();
        networkWorkers = Executors.newSingleThreadExecutor();
        aiExecutor = Executors.newSingleThreadExecutor();
        AiHttpTestControl.install(401);
    }

    @After public void tearDown() throws Exception {
        laneExecutor.shutdownNow();
        networkWorkers.shutdownNow();
        aiExecutor.shutdownNow();
        AiHttpTestControl.restore();
        AiRequestLiveState.clearForTest();
    }

    @Test public void orgApplePayloadFlowsThroughDetectionAndLocalPinyinWithoutAi() throws Exception {
        LyricsDocument document = parseOrgApplePayload();
        assertEquals("Apple Music", document.provider);
        assertLocalPinyinWithoutAi(document);
    }

    @Test public void applePayloadFlowsThroughDetectionAndLocalPinyinWithoutAi() throws Exception {
        assertLocalPinyinWithoutAi(parseApplePayload());
    }

    private void assertLocalPinyinWithoutAi(LyricsDocument document) throws Exception {
        assertEquals("音乐", document.lines.get(0).text);

        detectChineseRows(document);
        assertTrue(document.lines.get(0).detection.hasLanguage());
        assertEquals("zh", document.lines.get(0).detection.language);
        document.romanizationPending = true;
        assertTrue(LyricsLocalRomanizer.shouldLocalRomanize(true, "pinyin", document,
                document.lines.get(0), document.lines.get(0).text));
        assertEquals("yin yue", LyricsLocalRomanizer.romanizeLine(
                new RomanizationOptions("pinyin", "Off", false, "Off", false), document,
                document.lines.get(0), document.lines.get(0).text));

        RecordingCallback callback = new RecordingCallback();
        LyricsSoundLane lane = newLane();
        assertTrue(lane.start("ainy", 1, document, true,
                new RomanizationOptions("pinyin", "Off", false, "Off", false), null,
                "zh", false, (id, generation, snapshot) -> true, callback));

        SoundArtifact artifact = callback.await();
        assertNotNull(artifact);
        assertFalse("local pinyin should be complete", artifact.partial);
        assertEquals(1, artifact.size());
        assertNotNull(artifact.sound(CanonicalBase.fromDocument(document.trackId, document)
                .rows.get(0).rowId));
        assertEquals("yin yue", artifact.sound(
                CanonicalBase.fromDocument(document.trackId, document).rows.get(0).rowId)
                .renderPlan.joinedDisplayText);
        assertEquals("no AI request for complete local pinyin", 0, AiHttpTestControl.count());
        assertEquals("no AI attempt state for complete local pinyin", AiRequestLiveState.Phase.NONE,
                AiRequestLiveState.diagnosticSnapshot(LayerKind.SOUND).current.phase);
    }

    @Test public void appleAndOrgRepresentationsProduceTheSameCanonicalChineseWork() throws Exception {
        LyricsDocument org = parseOrgApplePayload();
        LyricsDocument apple = parseApplePayload();
        detectChineseRows(org);
        detectChineseRows(apple);
        assertEquals(org.lines.get(0).text, apple.lines.get(0).text);
        // Source identity remains part of the canonical key, while lyric content stays equivalent.
        assertTrue(!CanonicalBase.fromDocument(org.trackId, org).digest.equals(
                CanonicalBase.fromDocument(apple.trackId, apple).digest));
    }

    private LyricsSoundLane newLane() {
        return new LyricsSoundLane(context, new OkHttpClient(), laneExecutor, networkWorkers,
                aiExecutor, 1, Runnable::run, () -> 1_000L, ignored -> aiSettings);
    }

    private LyricsDocument parseOrgApplePayload() {
        return new LyricsParser(null).parseSpicyOrgLyrics(null, track(), orgPayload());
    }

    private LyricsDocument parseApplePayload() {
        return new LyricsParser(null).parseSpicyLyrics(null, track(), applePayload(), false);
    }

    private void detectChineseRows(LyricsDocument document) throws Exception {
        CanonicalBase base = CanonicalBase.fromDocument(document.trackId, document);
        RecordingCache cache = new RecordingCache();
        LyricsDetectionSession.Detector detector = (text, known) -> DetectionResult.detected(
                "", text, ScriptClassifier.ScriptClass.CHINESE, "zh", 1.0);
        Constructor<LyricsDetectionSession> constructor = LyricsDetectionSession.class
                .getDeclaredConstructor(Context.class, Executor.class,
                        LyricsDetectionSession.Poster.class, LyricsDetectionSession.Cache.class,
                        LyricsDetectionSession.Detector.class);
        constructor.setAccessible(true);
        Executor work = Runnable::run;
        LyricsDetectionSession.Poster poster = Runnable::run;
        LyricsDetectionSession session = constructor.newInstance(new Object[]{
                null, work, poster, cache, detector});
        CountDownLatch done = new CountDownLatch(1);
        session.start(base, 1, (candidate, generation) -> true, (ignored, artifact) -> {
            for (int i = 0; i < ignored.rows.size(); i++) {
                document.lines.get(i).detection = artifact.result(ignored.rows.get(i).rowId);
            }
            done.countDown();
        });
        assertTrue(done.await(WAIT_MS, TimeUnit.MILLISECONDS));
    }

    private static SpotifyTrack track() {
        return new SpotifyTrack("A.I.N.Y.", "G.E.M.", "Album",
                "spotify:track:" + TRACK_ID, 0, "", 0, null, 180000, false);
    }

    private static String orgPayload() {
        return "{\"Status\":200,\"Type\":\"object\",\"Body\":{"+
                "\"id\":\"" + TRACK_ID + "\",\"source\":\"apple_music\","+
                "\"Type\":\"Static\",\"Lines\":[{\"Text\":\"音乐\"}]}}";
    }

    private static String applePayload() {
        return "{\"queries\":[{\"operationId\":\"0\",\"result\":{"+
                "\"httpStatus\":200,\"format\":\"json\",\"data\":{"+
                "\"Type\":\"Static\",\"Language\":\"zh\",\"Lines\":[{\"Text\":\"音乐\"}]}}}]}";
    }

    private static final class RecordingCache implements LyricsDetectionSession.Cache {
        @Override public DetectionArtifact restore(Context context, CanonicalBase base) { return null; }
        @Override public boolean save(Context context, CanonicalBase base, DetectionArtifact artifact) { return true; }
    }

    private static final class RecordingCallback implements LyricsSecondaryProcessor.Callback {
        private final CountDownLatch done = new CountDownLatch(1);
        private volatile SoundArtifact artifact;
        @Override public void rerender(LayerKind layer, DerivedLayerArtifact partial, String message) { }
        @Override public void progress(String message) { }
        @Override public void complete(LayerKind layer, DerivedLayerArtifact result,
                                       LayerFailure failure, String message, int changed) {
            if (result instanceof SoundArtifact) artifact = (SoundArtifact) result;
            done.countDown();
        }
        SoundArtifact await() throws Exception {
            assertTrue(done.await(WAIT_MS, TimeUnit.MILLISECONDS));
            return artifact;
        }
    }

    private static final class IdentityCipher implements AiCredentialStore.Cipher {
        @Override public String encrypt(String plaintext) { return plaintext; }
        @Override public String decrypt(String ciphertext) { return ciphertext; }
        @Override public void clear() { }
    }
}
