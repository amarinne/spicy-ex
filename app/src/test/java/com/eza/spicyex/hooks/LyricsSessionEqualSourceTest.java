package com.eza.spicyex.hooks;

import static org.junit.Assert.*;

import com.eza.spicyex.SpotifyPlusConfig;
import com.eza.spicyex.SpotifyTrack;
import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.lyrics.LyricsLine;
import com.eza.spicyex.lyrics.language.ScriptClassifier;
import com.eza.spicyex.lyrics.processing.LyricsSecondaryProcessingSession;
import com.eza.spicyex.lyrics.session.*;
import com.eza.spicyex.testsupport.FakeAndroidContext;
import java.lang.reflect.*;
import java.util.*;
import org.junit.Test;

/** Exercises provider re-seating while the original processing input is still owned by a run. */
public class LyricsSessionEqualSourceTest {
    private static final String URI = "spotify:track:aaaaaaaaaaaaaaaaaaaaaa";

    @Test public void synchronousReplaysOwnTheirDocumentsBeforeQueuedPreparationRuns() throws Exception {
        Fixture fixture = new Fixture();
        List<LyricsDocument> received = new ArrayList<>();
        ArrayDeque<Runnable> work = new ArrayDeque<>();
        java.util.function.Consumer<LyricsDocument> enqueue = doc -> {
            received.add(doc);
            work.add(() -> doc.lines.get(0).romanizedText = "Prepared reading");
        };
        LyricsSessionManager.SessionSubscription subscription = fixture.manager.subscribe(
                new LyricsSessionManager.Listener() {
                    public void onSessionChanged(LyricsSessionManager.Snapshot snapshot) {}
                    public void onDocumentChanged(LyricsSessionManager.Snapshot snapshot, LyricsDocument doc) {
                        enqueue.accept(doc);
                    }
                });
        LyricsSessionManager.LyricsRequest request = fixture.manager.requestLyrics(track(),
                new NativeSpicyLyricsHook.LyricsResultCallback() {
                    public void onSuccess(LyricsDocument doc) { enqueue.accept(doc); }
                    public void onError(String error) { fail(error); }
                });
        try {
            assertEquals("both replay callbacks return without awaiting preparation", 2, work.size());
            assertNotSame(received.get(0), received.get(1));
            assertNotSame(fixture.original, received.get(0));
            assertNotSame(fixture.original, received.get(1));
            work.remove().run();
            assertEquals("", received.get(1).lines.get(0).romanizedText);
            work.remove().run();
            assertEquals("", fixture.original.lines.get(0).romanizedText);
            assertEquals("", fixture.project().lines.get(0).romanizedText);
        } finally {
            subscription.close();
            request.close();
        }
    }

    @Test public void equalSourceKeepsBothCapturedLayerCompletionsAndNewProviderInput() throws Exception {
        Fixture fixture = new Fixture();
        for (LayerKind layer : LayerKind.values()) {
            fixture.session = fixture.session.withLayer(layer, fixture.session.layer(layer)
                    .processing(LayerAuthority.AI, "test-config", "", "pending"));
        }
        set(fixture.manager, "session", fixture.session);
        LyricsDocument incoming = source("apple", "Apple Music", "apple_music");
        incoming.songWriters = "New writer";
        incoming.lines.get(0).providerTranslatedText = "Bonjour";
        incoming.lines.get(0).romanizedText = "Provider reading";
        assertEquals(fixture.session.base.digest, CanonicalBase.fromDocument(URI, incoming).digest);

        fixture.publish(incoming);
        assertSame("both lanes still own the original input", fixture.original,
                get(fixture.manager, "document"));
        assertSame(fixture.session, get(fixture.manager, "session"));
        assertEquals("Apple Music", fixture.original.provider);
        assertEquals("apple_music", fixture.original.fetchSource);
        assertEquals("apple", fixture.original.catalogCandidateId);
        assertEquals("Bonjour", fixture.original.lines.get(0).providerTranslatedText);
        assertEquals("Provider reading", fixture.original.lines.get(0).romanizedText);
        for (LayerKind layer : LayerKind.values()) fixture.complete(layer);

        LyricSession settled = (LyricSession) get(fixture.manager, "session");
        assertEquals(LayerStatus.READY, settled.sound.status);
        assertEquals(LayerStatus.READY, settled.meaning.status);
        assertEquals(fixture.session.identity.sourceRevision, settled.identity.sourceRevision);
        LyricsDocument displayed = fixture.project();
        assertEquals("New writer", displayed.songWriters);
        assertEquals("Apple Music", displayed.provider);
        assertEquals("Hello translated", displayed.lines.get(0).translatedText);
        assertEquals("Hello reading", displayed.lines.get(0).romanizedText);
        assertFalse(displayed.processingPending);
    }

    @Test public void equalSourceWhileDetectionIsQueuedStillStartsTheDerivedLayers() throws Exception {
        Fixture fixture = new Fixture();
        call(fixture.manager, "startDetection", new Class<?>[]{SpotifyTrack.class,
                LyricsDocument.class, int.class}, track(), fixture.original, fixture.generation);
        assertTrue((Boolean) get(fixture.manager, "awaitingDetection"));
        fixture.publish(source("apple", "Apple Music", "apple_music"));
        assertEquals("equal content must share the queued detection", 1, fixture.detectionWork.size());
        fixture.detectionWork.remove().run();

        assertFalse("detection completion must release its initial-processing gate",
                (Boolean) get(fixture.manager, "awaitingDetection"));
        assertNotNull(fixture.original.lines.get(0).detection);
        assertNotNull(((LyricsDocument) get(fixture.manager, "canonicalSource")).lines.get(0).detection);
        assertEquals("en", fixture.project().lines.get(0).detection.language);
    }

    @Test public void equalSourceKeepsCompletedDetectionOnTheNewProviderRows() throws Exception {
        Fixture fixture = new Fixture();
        DetectionResult detected = DetectionResult.detected(fixture.session.base.rowAt(0).rowId,
                fixture.original.lines.get(0).text, ScriptClassifier.ScriptClass.LATIN, "en", 1d);
        DetectionArtifact artifact = new DetectionArtifact(fixture.session.base.digest,
                DetectionArtifact.DETECTOR_POLICY_ID, Collections.singletonList(detected), false);
        set(fixture.manager, "detectionArtifact", artifact);
        fixture.original.lines.get(0).detection = detected;

        fixture.publish(source("apple", "Apple Music", "apple_music"));
        assertSame(detected, fixture.original.lines.get(0).detection);
        assertSame(detected, ((LyricsDocument) get(fixture.manager, "canonicalSource")).lines.get(0).detection);
    }

    @Test public void replacingCanonicalTextStillRejectsThePreviousLayerCompletion() throws Exception {
        Fixture fixture = new Fixture();
        fixture.session = fixture.session.withMeaning(fixture.session.meaning
                .processing(LayerAuthority.AI, "test-config", "", "pending"));
        set(fixture.manager, "session", fixture.session);
        LyricsDocument incoming = source("apple", "Apple Music", "apple_music");
        incoming.lines.get(0).text = "A different lyric";
        fixture.publish(incoming);
        fixture.complete(LayerKind.MEANING);

        LyricSession replaced = (LyricSession) get(fixture.manager, "session");
        assertNotEquals(fixture.session.base.digest, replaced.base.digest);
        assertEquals(fixture.session.identity.sourceRevision + 1, replaced.identity.sourceRevision);
        assertEquals(LayerStatus.ABSENT, replaced.meaning.status);
        assertEquals("", fixture.project().lines.get(0).translatedText);
    }

    private static final class Fixture {
        final FakeAndroidContext context = new FakeAndroidContext();
        final ArrayDeque<Runnable> detectionWork = new ArrayDeque<>();
        final LyricsDocument original = source("native", "Spotify", "spotify_native_model");
        final LyricsSessionManager manager;
        final int generation;
        LyricSession session;

        Fixture() throws Exception {
            Constructor<LyricsDetectionSession> constructor = LyricsDetectionSession.class.getDeclaredConstructor(
                    android.content.Context.class, java.util.concurrent.Executor.class,
                    LyricsDetectionSession.Poster.class, LyricsDetectionSession.Cache.class,
                    LyricsDetectionSession.Detector.class);
            constructor.setAccessible(true);
            LyricsDetectionSession detection = constructor.newInstance(context,
                    (java.util.concurrent.Executor) detectionWork::add,
                    (LyricsDetectionSession.Poster) Runnable::run, new LyricsDetectionSession.Cache() {
                        public DetectionArtifact restore(android.content.Context c, CanonicalBase b) { return null; }
                        public boolean save(android.content.Context c, CanonicalBase b, DetectionArtifact a) { return false; }
                    }, (LyricsDetectionSession.Detector) (text, known) -> DetectionResult.detected(
                            "", text, ScriptClassifier.ScriptClass.LATIN, "en", 1d));
            // English needs neither reading nor translation. Reaching the real initial lane start
            // therefore settles without executors, Android's Looper, or a provider request.
            LyricsSecondaryProcessingSession processing = new LyricsSecondaryProcessingSession(
                    context, SpotifyPlusConfig.from(context), null, 1, "test");
            manager = new LyricsSessionManager(context, null, processing, detection, () -> 0L);
            LyricsSessionPolicy policy = (LyricsSessionPolicy) get(manager, "policy");
            policy.adoptTrack(URI);
            generation = policy.generation();
            session = LyricSession.of(CanonicalBase.fromDocument(URI, original), generation, 3);
            set(manager, "document", original);
            set(manager, "canonicalSource", LyricsDocument.copyOf(original));
            set(manager, "displayedCandidateId", original.catalogCandidateId);
            set(manager, "session", session);
        }

        void publish(LyricsDocument incoming) throws Exception {
            assertEquals(true, call(manager, "publishSeat", new Class<?>[]{SpotifyTrack.class,
                    String.class, int.class, LyricsDocument.class, int.class}, track(), URI,
                    generation, incoming, 9));
        }

        void complete(LayerKind layer) throws Exception {
            LayerProvenance provenance = new LayerProvenance(LayerAuthority.AI, "test", "test-config", 0L);
            String rowId = session.base.rowAt(0).rowId;
            DerivedLayerArtifact artifact = layer == LayerKind.MEANING
                    ? new MeaningArtifact(session.base.digest, "test-config", provenance,
                            Collections.singletonList(new MeaningEntry(rowId, "Hello translated", "en")), false)
                    : new SoundArtifact(session.base.digest, "test-config", provenance,
                            Collections.singletonList(SoundEntry.line(rowId, "Hello reading", "latin")), false);
            call(manager, "adoptLayerArtifact", new Class<?>[]{LayerKind.class, DerivedLayerArtifact.class,
                    LayerFailure.class, LyricsDocument.class, int.class}, layer, artifact,
                    LayerFailure.NONE, original, generation);
        }

        LyricsDocument project() throws Exception {
            return (LyricsDocument) call(manager, "publishedProjection", new Class<?>[]{LyricsDocument.class},
                    get(manager, "document"));
        }
    }

    private static LyricsDocument source(String candidate, String provider, String fetchSource) {
        LyricsDocument document = new LyricsDocument();
        document.trackId = "aaaaaaaaaaaaaaaaaaaaaa";
        document.language = "en";
        document.type = "Line";
        document.durationMs = 60000;
        document.catalogCandidateId = candidate;
        document.provider = provider;
        document.fetchSource = fetchSource;
        LyricsLine line = new LyricsLine();
        line.text = "Hello world";
        line.startMs = 1000;
        line.endMs = 3000;
        document.lines.add(line);
        return document;
    }

    private static SpotifyTrack track() {
        return new SpotifyTrack("Title", "Artist", "Album", URI, 0, "", 0, "", 60000, false);
    }

    private static Object get(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static void set(Object owner, String name, Object value) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(owner, value);
    }

    private static Object call(Object owner, String name, Class<?>[] parameters, Object... arguments) throws Exception {
        Method method = owner.getClass().getDeclaredMethod(name, parameters);
        method.setAccessible(true);
        return method.invoke(owner, arguments);
    }
}
