package com.eza.spicyex.hooks;

import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.lyrics.LyricsLine;
import com.eza.spicyex.lyrics.SyllableSegment;
import com.eza.spicyex.lyrics.blend.SyncUpgradeEngine;
import com.eza.spicyex.lyrics.catalog.*;
import com.eza.spicyex.lyrics.session.*;
import com.eza.spicyex.testsupport.FakeAndroidContext;
import java.lang.reflect.*;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Exercises the session's actual scheduling, result gate, and publication boundaries. */
public class LyricsSessionAnchorRoutingTest {
    private static final String URI = "spotify:track:aaaaaaaaaaaaaaaaaaaaaa";

    @Test public void matchingStaleCompletionSettlesItsGateButOldOrManualCompletionCannot() throws Exception {
        for (long callbackAttempt : new long[]{0, 4, 5}) {
            LyricsSessionManager manager = manager();
            LyricsSessionPolicy policy = (LyricsSessionPolicy) get(manager, "policy");
            policy.adoptTrack(URI);
            set(manager, "loadingUri", URI);
            set(manager, "automaticAttemptId", 5L);
            set(manager, "appliedViewSequence", 2L);
            manager.acceptProviderView(null, URI, policy.generation(), new LyricsDocument(),
                    null, false, view(CatalogState.empty("test"), new CatalogPolicy(Collections.emptyList(), false),
                            null, 1L), false, callbackAttempt);
            assertEquals(callbackAttempt == 5 ? "" : URI, get(manager, "loadingUri"));
            assertEquals(2L, get(manager, "appliedViewSequence"));
        }
    }

    @Test public void fallbackWalkUsesVisitHistoryAndOnlyRepeatedScopeWaits() throws Exception {
        LyricsSessionManager manager = manager();
        CatalogPolicy policy = new CatalogPolicy(Arrays.asList(CatalogSource.SourceId.APPLE,
                CatalogSource.SourceId.SPOTIFY_NATIVE, CatalogSource.SourceId.LRCLIB), false);
        CatalogState state = CatalogState.empty("test");
        Set<CatalogSource.SourceId> attempted = (Set<CatalogSource.SourceId>) get(manager, "attemptedSources");
        attempted.add(CatalogSource.SourceId.APPLE);
        set(manager, "lastAutomaticScope", new AcquisitionScope(Collections.singletonList(CatalogSource.SourceId.APPLE), true));
        call(manager, "schedule", new Class<?>[]{LyricsCatalog.View.class, boolean.class}, view(state, policy, null, 1L), true);
        AcquisitionPlanner.Plan plan = (AcquisitionPlanner.Plan) get(manager, "pendingPlan");
        assertEquals(Collections.singletonList(CatalogSource.SourceId.SPOTIFY_NATIVE), plan.scope.sources);
        long immediate = (long) get(manager, "nextFetchAtMs");
        set(manager, "lastAutomaticScope", plan.scope);
        call(manager, "schedule", new Class<?>[]{LyricsCatalog.View.class, boolean.class}, view(state, policy, null, 2L), true);
        assertEquals(immediate + AcquisitionPlanner.TRANSIENT_BASE_RETRY_MS, get(manager, "nextFetchAtMs"));
    }

    @Test public void sameCandidateRefetchRenewsDisplayedClockAndKeepsSessionArtifacts() throws Exception {
        LyricsSessionManager manager = manager();
        LyricsSessionPolicy policy = (LyricsSessionPolicy) get(manager, "policy");
        policy.adoptTrack(URI);
        LyricsDocument old = anchor();
        old.catalogCandidateId = "same";
        LyricSession session = session(old);
        set(manager, "document", old);
        set(manager, "canonicalSource", LyricsDocument.copyOf(old));
        set(manager, "displayedCandidateId", "same");
        set(manager, "session", session);
        LyricsDocument fresh = LyricsDocument.copyOf(old);
        fresh.spicyOrgFetchedAtMs = System.currentTimeMillis();
        fresh.spicyOrgRawPayload = "fresh marks";
        assertEquals(true, call(manager, "publishSeat", new Class<?>[]{com.eza.spicyex.SpotifyTrack.class,
                String.class, int.class, LyricsDocument.class, int.class}, null, URI, policy.generation(), fresh, 1));
        assertSame(session, get(manager, "session"));
        assertSame("an in-flight layer must retain its captured processing document", old, get(manager, "document"));
        LyricsDocument displayed = (LyricsDocument) call(manager, "publishedProjection", new Class<?>[]{LyricsDocument.class}, fresh);
        assertEquals(fresh.spicyOrgFetchedAtMs, displayed.spicyOrgFetchedAtMs);
        assertEquals("fresh marks", displayed.spicyOrgRawPayload);
        assertEquals("Authoritative reading", displayed.lines.get(0).romanizedText);
        assertEquals("Authoritative translation", displayed.lines.get(0).translatedText);
        set(manager, "session", session.withMeaning(session.meaning.processing(
                LayerAuthority.AI, "test", "", "pending")));
        call(manager, "adoptLayerArtifact", new Class<?>[]{LayerKind.class, DerivedLayerArtifact.class,
                LayerFailure.class, LyricsDocument.class, int.class}, LayerKind.MEANING,
                session.meaning.artifact, LayerFailure.NONE, old, policy.generation());
        assertEquals(LayerStatus.READY, ((LyricSession) get(manager, "session")).meaning.status);
    }

    @Test public void projectionAddsTimingAfterCompositionAndExpiresWithOrgAnchor() throws Exception {
        LyricsSessionManager manager = manager();
        LyricsDocument anchor = anchor(), donor = donor(anchor);
        LyricsDocument timing = SyncUpgradeEngine.upgrade(anchor.trackId, 90000, anchor, donor,
                System.currentTimeMillis()).document;
        assertNotNull(timing);
        set(manager, "document", anchor);
        set(manager, "canonicalSource", anchor);
        LyricSession session = session(anchor);
        set(manager, "session", session);
        set(manager, "timingProjection", timing);
        LyricsDocument rendered = (LyricsDocument) call(manager, "publishedProjection", new Class<?>[]{LyricsDocument.class}, anchor);
        assertNotNull(rendered.syncUpgradeProvenance);
        assertFalse(rendered.lines.get(0).syllables.isEmpty());
        assertEquals("[HELLO, sunshine!]", rendered.lines.get(0).text);
        assertEquals("Authoritative reading", rendered.lines.get(0).romanizedText);
        assertEquals("Authoritative translation", rendered.lines.get(0).translatedText);
        assertTrue(anchor.lines.get(0).syllables.isEmpty());
        assertEquals(CanonicalBase.fromDocument(URI, anchor).digest, session.identity.canonicalDigest);
        anchor.spicyOrgFetchedAtMs = System.currentTimeMillis() - 30L * 86400000L;
        assertNull(call(manager, "publishedProjection", new Class<?>[]{LyricsDocument.class}, anchor));
    }

    @Test public void firstWaitingRequestReceivesTheSameTimingProjectionAsSubscribers() throws Exception {
        LyricsDetectionSession.Cache cache = new LyricsDetectionSession.Cache() {
            public DetectionArtifact restore(android.content.Context c, CanonicalBase b) { return null; }
            public boolean save(android.content.Context c, CanonicalBase b, DetectionArtifact a) { return false; }
        };
        Constructor<LyricsDetectionSession> detectionConstructor = LyricsDetectionSession.class.getDeclaredConstructor(
                android.content.Context.class, java.util.concurrent.Executor.class, LyricsDetectionSession.Poster.class,
                LyricsDetectionSession.Cache.class, LyricsDetectionSession.Detector.class);
        detectionConstructor.setAccessible(true);
        FakeAndroidContext context = new FakeAndroidContext();
        LyricsDetectionSession detection = detectionConstructor.newInstance(context,
                (java.util.concurrent.Executor) ignored -> {}, (LyricsDetectionSession.Poster) Runnable::run,
                cache, (LyricsDetectionSession.Detector) (text, known) -> known);
        LyricsSessionManager manager = new LyricsSessionManager(context, null, null, detection, () -> 0L);
        LyricsSessionPolicy policy = (LyricsSessionPolicy) get(manager, "policy");
        policy.adoptTrack(URI);
        LyricsDocument anchor = anchor();
        set(manager, "timingProjection", SyncUpgradeEngine.upgrade(anchor.trackId, 90000,
                anchor, donor(anchor), System.currentTimeMillis()).document);
        LyricsDocument[] received = new LyricsDocument[1];
        NativeSpicyLyricsHook.LyricsResultCallback callback = new NativeSpicyLyricsHook.LyricsResultCallback() {
            public void onSuccess(LyricsDocument document) { received[0] = document; }
            public void onError(String error) { fail(error); }
        };
        Class<?> requestType = Class.forName("com.eza.spicyex.hooks.LyricsSessionManager$RequestRecord");
        Constructor<?> requestConstructor = requestType.getDeclaredConstructor(LyricsSessionManager.class,
                int.class, NativeSpicyLyricsHook.LyricsResultCallback.class);
        requestConstructor.setAccessible(true);
        ((List<Object>) get(manager, "requests")).add(requestConstructor.newInstance(manager, policy.generation(), callback));
        call(manager, "publishSeat", new Class<?>[]{com.eza.spicyex.SpotifyTrack.class, String.class,
                int.class, LyricsDocument.class, int.class}, null, URI, policy.generation(), anchor, 1);
        assertNotNull(received[0]);
        assertNotNull(received[0].syncUpgradeProvenance);
        assertFalse(received[0].lines.get(0).syllables.isEmpty());
        assertEquals("[HELLO, sunshine!]", received[0].lines.get(0).text);
        assertTrue(anchor.lines.get(0).syllables.isEmpty());
    }

    private static LyricSession session(LyricsDocument anchor) {
        CanonicalBase base = CanonicalBase.fromDocument(URI, anchor);
        LayerProvenance provenance = new LayerProvenance(LayerAuthority.AI, "test", "test", 0L);
        String row = base.rowAt(0).rowId;
        return LyricSession.of(base, 1).withSound(LayerState.absent(LayerKind.SOUND).withArtifact(
                LayerStatus.READY, new SoundArtifact(base.digest, "test", provenance,
                        Collections.singletonList(SoundEntry.line(row, "Authoritative reading", "latin")), false), ""))
                .withMeaning(LayerState.absent(LayerKind.MEANING).withArtifact(LayerStatus.READY,
                        new MeaningArtifact(base.digest, "test", provenance,
                                Collections.singletonList(new MeaningEntry(row, "Authoritative translation", "en")), false), ""));
    }

    private static LyricsDocument anchor() {
        LyricsDocument anchor = new LyricsDocument();
        anchor.trackId = "aaaaaaaaaaaaaaaaaaaaaa";
        anchor.durationMs = 90000;
        anchor.type = "Line";
        anchor.provider = "Apple Music";
        anchor.fetchSource = "spicy_org";
        anchor.spicyOrgSource = "apple_music";
        anchor.catalogDelivery = new CatalogDelivery(CatalogSource.SourceId.SPICY_ORG,
                anchor.trackId, CatalogSource.MatchMethod.EXACT_SPOTIFY_ID, true);
        anchor.spicyOrgFetchedAtMs = System.currentTimeMillis() - 22L * 86400000L;
        String[] texts = {"[HELLO, sunshine!]", "Welcome home", "Stay forever"};
        for (int i = 0; i < texts.length; i++) {
            LyricsLine row = new LyricsLine();
            row.text = texts[i]; row.startMs = 10000 + i * 15000; row.endMs = row.startMs + 3000;
            anchor.lines.add(row);
        }
        return anchor;
    }

    private static LyricsDocument donor(LyricsDocument anchor) {
        LyricsDocument donor = LyricsDocument.copyOf(anchor);
        donor.fetchSource = "netease"; donor.provider = "NetEase"; donor.type = "Word";
        donor.spicyOrgSource = ""; donor.spicyOrgFetchedAtMs = 0;
        donor.catalogDelivery = new CatalogDelivery(CatalogSource.SourceId.NETEASE,
                "fixture-donor", CatalogSource.MatchMethod.STRONG_SEARCH, true);
        for (LyricsLine row : donor.lines) {
            row.text = row.text.equals("[HELLO, sunshine!]") ? "Hello sunshine" : row.text;
            row.startMs -= 500; row.endMs -= 500;
            String[] words = row.text.split(" ");
            for (int i = 0; i < words.length; i++) {
                SyllableSegment span = new SyllableSegment();
                span.text = words[i]; span.startMs = row.startMs + i * 500;
                span.endMs = span.startMs + 400; span.totalMs = 400; span.boundaryAfter = true;
                row.syllables.add(span);
            }
        }
        return donor;
    }

    private static LyricsSessionManager manager() {
        return new LyricsSessionManager(new FakeAndroidContext(), null, null, null, () -> 0L);
    }
    private static LyricsCatalog.View view(CatalogState state, CatalogPolicy policy, LyricsDocument document, long sequence) throws Exception {
        Constructor<LyricsCatalog.View> constructor = LyricsCatalog.View.class.getDeclaredConstructor(String.class,
                CatalogState.class, CatalogPolicy.class, CatalogResolver.Resolution.class, AcquisitionPlanner.Plan.class,
                LyricsDocument.class, int.class, boolean.class, long.class, String.class);
        constructor.setAccessible(true);
        return constructor.newInstance("test", state, policy, null,
                AcquisitionPlanner.plan(state, policy, null, System.currentTimeMillis(), true), document, 1, true, sequence, "");
    }
    private static Object call(Object owner, String name, Class<?>[] parameters, Object... values) throws Exception {
        Method method = owner.getClass().getDeclaredMethod(name, parameters);
        method.setAccessible(true);
        return method.invoke(owner, values);
    }
    private static Object get(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner);
    }
    private static void set(Object owner, String name, Object value) throws Exception {
        Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); field.set(owner, value);
    }
}
