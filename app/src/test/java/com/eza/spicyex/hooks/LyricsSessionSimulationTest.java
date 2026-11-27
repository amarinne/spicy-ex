package com.eza.spicyex.hooks;

import android.content.Context;
import android.os.Handler;
import android.os.SystemClock;

import com.eza.spicyex.Settings;
import com.eza.spicyex.SpotifyPlusConfig;
import com.eza.spicyex.SpotifyTrack;
import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.lyrics.LyricsLine;
import com.eza.spicyex.lyrics.catalog.AcquisitionPlanner;
import com.eza.spicyex.lyrics.catalog.CatalogCandidate;
import com.eza.spicyex.lyrics.catalog.CatalogDecisions;
import com.eza.spicyex.lyrics.catalog.CatalogPolicy;
import com.eza.spicyex.lyrics.catalog.CatalogResolver;
import com.eza.spicyex.lyrics.catalog.CatalogSelection;
import com.eza.spicyex.lyrics.catalog.CatalogSource.MatchMethod;
import com.eza.spicyex.lyrics.catalog.CatalogSource.ProviderStatus;
import com.eza.spicyex.lyrics.catalog.CatalogSource.SelectionMode;
import com.eza.spicyex.lyrics.catalog.CatalogSource.SourceId;
import com.eza.spicyex.lyrics.catalog.CatalogSource.TimingLevel;
import com.eza.spicyex.lyrics.catalog.CatalogState;
import com.eza.spicyex.lyrics.catalog.LyricsCatalog;
import com.eza.spicyex.lyrics.catalog.ProviderRecord;
import com.eza.spicyex.lyrics.language.ScriptClassifier;
import com.eza.spicyex.lyrics.processing.LyricsSecondaryProcessingSession;
import com.eza.spicyex.lyrics.processing.LyricsSecondaryProcessor;
import com.eza.spicyex.lyrics.providers.SpicyOrgAccessState;
import com.eza.spicyex.lyrics.providers.SpicyOrgPolicy;
import com.eza.spicyex.lyrics.session.CanonicalBase;
import com.eza.spicyex.lyrics.session.CanonicalSourceCodec;
import com.eza.spicyex.lyrics.session.DetectionArtifact;
import com.eza.spicyex.lyrics.session.DetectionResult;
import com.eza.spicyex.lyrics.session.LayerAuthority;
import com.eza.spicyex.lyrics.session.LayerFailure;
import com.eza.spicyex.lyrics.session.LayerKind;
import com.eza.spicyex.lyrics.session.LayerProvenance;
import com.eza.spicyex.lyrics.session.LayerState;
import com.eza.spicyex.lyrics.session.LayerStatus;
import com.eza.spicyex.lyrics.session.LyricSession;
import com.eza.spicyex.lyrics.session.LyricsDetectionSession;
import com.eza.spicyex.lyrics.session.MeaningArtifact;
import com.eza.spicyex.lyrics.session.MeaningEntry;
import com.eza.spicyex.lyrics.session.SoundArtifact;
import com.eza.spicyex.lyrics.session.SoundEntry;
import com.eza.spicyex.testsupport.FakeAndroidContext;

import org.junit.After;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Deterministic Simulation Tests (DST) for session lifecycle, catalog acquisition planning,
 * detection/derived-lane interleavings, org-access termination, and prefetch joins.
 *
 * <p>Set {@code -Dspicy.dst.seed=<long>} to replay a single failing seed directly.
 */
public class LyricsSessionSimulationTest {

    private static final int SESSION_SEEDS = 24;
    private static final int SESSION_STEPS = 55;
    private static final int CATALOG_SEEDS = 150;

    private static final String[] TRACK_URIS = {
            "spotify:track:1111111111111111111111",
            "spotify:track:2222222222222222222222",
            "spotify:track:3333333333333333333333",
            "spotify:track:4444444444444444444444"
    };

    private static final String[][] LYRIC_VARIANTS = {
            {"君の声が聞こえる", "夜の風が吹く"},
            {"Hello from the sky", "Waiting for sunrise"},
            {"愛しているよ", "ずっとそばにいる"},
            {"Привет мир", "Тихая ночь"}
    };

    private static final SourceId[] AUTO_SOURCES = {
            SourceId.SPICY_ORG, SourceId.APPLE, SourceId.SPOTIFY_NATIVE,
            SourceId.AMLL, SourceId.LRCLIB, SourceId.QQ, SourceId.NETEASE
    };

    @After
    public void resetClock() {
        SystemClock.setElapsedRealtimeForTest(1000L);
    }

    @Test
    public void randomizedSessionInterleavingsPreserveAllLifecycleInvariants() throws Exception {
        for (long seed : seeds(SESSION_SEEDS, 0x51C001L)) {
            runSessionSimulation(seed);
        }
    }

    @Test
    public void randomizedCatalogAndPlannerPermutationsPreservePinAndOrderInvariants() {
        for (long seed : seeds(CATALOG_SEEDS, 0xCA7A106L)) {
            runCatalogPlannerSimulation(seed);
        }
    }

    @Test
    public void randomizedPrefetchJoinAndMismatchNeverLeaksStaleLayers() throws Exception {
        for (long seed : seeds(60, 0x9EF37C4L)) {
            runPrefetchSimulation(seed);
        }
    }

    private void runSessionSimulation(long seed) throws Exception {
        Random rng = new Random(seed);
        SimClock clock = new SimClock(10_000L);
        SystemClock.setElapsedRealtimeForTest(clock.now);
        FakeAndroidContext context = new FakeAndroidContext();
        SimScheduler scheduler = new SimScheduler(rng);
        SimExecutorService executor = new SimExecutorService(scheduler);

        LyricsDetectionSession detection = newDetectionSession(context, scheduler);
        LyricsSecondaryProcessor processor = new LyricsSecondaryProcessor(
                context, new OkHttpClient(), executor, executor, executor, executor,
                new Handler(), 1);
        LyricsSecondaryProcessingSession secondary = new LyricsSecondaryProcessingSession(
                context, SpotifyPlusConfig.from(context), processor, 1, "[DST]");
        LyricsSessionManager manager = new LyricsSessionManager(
                context, new Handler(), secondary, detection, clock::nowMs);

        int[] lastObservedGen = {0};
        String[] lastObservedUri = {""};
        LyricsSessionManager.SessionSubscription sub = manager.subscribe(
                new LyricsSessionManager.Listener() {
                    @Override public void onSessionChanged(LyricsSessionManager.Snapshot snapshot) {
                        assertTrue("seed=" + seed + " generation regressed: "
                                        + snapshot.generation + " < " + lastObservedGen[0],
                                snapshot.generation >= lastObservedGen[0]);
                        lastObservedGen[0] = snapshot.generation;
                        lastObservedUri[0] = snapshot.trackUri;
                    }

                    @Override public void onDocumentChanged(LyricsSessionManager.Snapshot snapshot,
                                                            LyricsDocument doc) {
                        assertEquals("seed=" + seed + " document published for stale generation",
                                lastObservedGen[0], snapshot.generation);
                        assertEquals("seed=" + seed + " document published for mismatched URI",
                                lastObservedUri[0], snapshot.trackUri);
                        if (doc != null) {
                            assertFalse("seed=" + seed + " restricted org doc published after termination",
                                    SpicyOrgPolicy.isRestricted(doc)
                                            && SpicyOrgAccessState.isTerminated(context));
                        }
                    }
                });

        List<PendingProviderEvent> pendingViews = new ArrayList<>();
        List<PendingLayerEvent> pendingLayers = new ArrayList<>();
        long viewSeq = 1L;
        int currentTrackIdx = -1;
        SpotifyTrack currentTrack = null;

        try {
            for (int step = 0; step < SESSION_STEPS; step++) {
                clock.advance(50L + rng.nextInt(500));
                SystemClock.setElapsedRealtimeForTest(clock.now);
                String tag = "seed=" + seed + " step=" + step;
                int action = currentTrack == null ? 0 : rng.nextInt(10);

                switch (action) {
                    case 0: { // Switch track (or start first track)
                        currentTrackIdx = rng.nextInt(TRACK_URIS.length);
                        currentTrack = track(TRACK_URIS[currentTrackIdx], "Song " + currentTrackIdx);
                        adoptTrackForTest(manager, currentTrack);
                        // Enqueue 1..2 delayed provider views for this track/generation.
                        int gen = policyOf(manager).generation();
                        int count = 1 + rng.nextInt(2);
                        for (int i = 0; i < count; i++) {
                            boolean org = rng.nextInt(4) == 0;
                            boolean readable = rng.nextInt(8) != 0;
                            int variant = (currentTrackIdx + (rng.nextBoolean() ? 0 : 1)) % LYRIC_VARIANTS.length;
                            LyricsDocument doc = buildDoc(currentTrack.uri, variant, org, "cand-" + viewSeq);
                            CatalogPolicy policy = CatalogPolicy.read(context);
                            LyricsCatalog.View view = newCatalogView(currentTrack.uri, policy, doc,
                                    1, readable, true, viewSeq++);
                            pendingViews.add(new PendingProviderEvent(currentTrack, currentTrack.uri,
                                    gen, doc, view));
                        }
                        break;
                    }
                    case 1:
                    case 2: { // Deliver a random pending provider view (possibly stale/out-of-order)
                        if (!pendingViews.isEmpty()) {
                            PendingProviderEvent ev = pendingViews.remove(rng.nextInt(pendingViews.size()));
                            int genBefore = policyOf(manager).generation();
                            String uriBefore = policyOf(manager).trackUri();
                            long seqBefore = getLongField(manager, "appliedViewSequence");
                            boolean wasTerminated = SpicyOrgAccessState.isTerminated(context);
                            manager.acceptProviderView(ev.track, ev.uri, ev.generation, ev.doc,
                                    null, true, ev.view, false, 0L);
                            if (ev.generation != genBefore || !ev.uri.equals(uriBefore)) {
                                assertEquals(tag + " stale provider view mutated appliedViewSequence",
                                        seqBefore, getLongField(manager, "appliedViewSequence"));
                            }
                            if (wasTerminated && ev.view.policy != null
                                    && !ev.view.policy.hasCurrentOrgAccess(context)) {
                                assertEquals(tag + " pre-termination policy view must not advance sequence",
                                        seqBefore, getLongField(manager, "appliedViewSequence"));
                            }
                            // Optionally enqueue simulated layer completion against the active doc.
                            LyricsDocument liveDoc = (LyricsDocument) getField(manager, "document");
                            LyricSession liveSession = (LyricSession) getField(manager, "session");
                            if (liveDoc != null && liveSession != null) {
                                LayerKind layer = rng.nextBoolean() ? LayerKind.SOUND : LayerKind.MEANING;
                                pendingLayers.add(new PendingLayerEvent(
                                        layer, liveDoc, liveSession.base,
                                        policyOf(manager).generation(), rng.nextBoolean()));
                            }
                        }
                        break;
                    }
                    case 3:
                    case 4: { // Step deterministic scheduler (detection / local sound tasks)
                        scheduler.runRandom(1 + rng.nextInt(3));
                        break;
                    }
                    case 5: { // Deliver a possibly-stale Sound or Meaning layer completion
                        if (!pendingLayers.isEmpty()) {
                            PendingLayerEvent ev = pendingLayers.remove(rng.nextInt(pendingLayers.size()));
                            LyricsDocument beforeDoc = (LyricsDocument) getField(manager, "document");
                            LyricSession beforeSession = (LyricSession) getField(manager, "session");
                            LayerKind sibling = ev.layer == LayerKind.SOUND ? LayerKind.MEANING : LayerKind.SOUND;
                            LayerState siblingBefore = beforeSession == null ? null : beforeSession.layer(sibling);
                            int currentGen = policyOf(manager).generation();

                            if (ev.layer == LayerKind.SOUND) {
                                SoundArtifact art = ev.fail ? null : new SoundArtifact(
                                        ev.base.digest, "sound-cfg",
                                        new LayerProvenance(LayerAuthority.DETERMINISTIC, "dst", "sound-cfg", clock.now),
                                        Collections.singletonList(SoundEntry.line(
                                                ev.base.rowAt(0).rowId, "dst-romaji", "")),
                                        false);
                                invokeAdoptLayerArtifact(manager, LayerKind.SOUND, art,
                                        ev.fail ? LayerFailure.of(LayerFailure.Reason.SERVER_ERROR) : LayerFailure.NONE,
                                        ev.doc, ev.generation);
                            } else {
                                MeaningArtifact art = ev.fail ? null : new MeaningArtifact(
                                        ev.base.digest, "meaning-cfg",
                                        new LayerProvenance(LayerAuthority.MACHINE, "dst", "meaning-cfg", clock.now),
                                        Collections.singletonList(new MeaningEntry(
                                                ev.base.rowAt(0).rowId, "dst-translation", "en")),
                                        false);
                                invokeAdoptLayerArtifact(manager, LayerKind.MEANING, art,
                                        ev.fail ? LayerFailure.of(LayerFailure.Reason.SERVER_ERROR) : LayerFailure.NONE,
                                        ev.doc, ev.generation);
                            }

                            LyricSession afterSession = (LyricSession) getField(manager, "session");
                            if (beforeSession != null && afterSession != null) {
                                assertEquals(tag + " sibling layer mutated by " + ev.layer + " completion",
                                        siblingBefore.status, afterSession.layer(sibling).status);
                                assertEquals(tag + " sibling artifact mutated by " + ev.layer + " completion",
                                        siblingBefore.artifact, afterSession.layer(sibling).artifact);
                            }
                            if (ev.doc != beforeDoc || ev.generation != currentGen) {
                                assertEquals(tag + " stale layer completion mutated session",
                                        beforeSession, afterSession);
                            }
                        }
                        break;
                    }
                    case 6: { // Equal-digest vs different-digest provider re-seat
                        LyricsDocument liveDoc = (LyricsDocument) getField(manager, "document");
                        LyricSession liveSession = (LyricSession) getField(manager, "session");
                        if (liveDoc != null && liveSession != null && currentTrack != null) {
                            boolean trySameText = rng.nextBoolean();
                            LyricsDocument incoming = LyricsDocument.copyOf(liveDoc);
                            incoming.catalogCandidateId = "reseat-" + step;
                            incoming.provider = trySameText ? "Apple Music" : "LRCLIB";
                            incoming.fetchSource = trySameText ? "apple_music" : "lrclib";
                            if (!trySameText) {
                                incoming.lines.get(0).text = "Changed line " + step;
                            }
                            String uri = policyOf(manager).trackUri();
                            boolean expectedSameDigest = liveSession.base.digest.equals(
                                    CanonicalBase.fromDocument(uri, incoming).digest);
                            if (liveDoc.fetchSource != null && liveDoc.fetchSource.startsWith("spicy_org")) {
                                assertFalse(tag + " spicy_org digest must never match non-org provider",
                                        expectedSameDigest);
                            }
                            int revBefore = liveSession.identity.sourceRevision;
                            invokePublishSeat(manager, currentTrack, uri,
                                    policyOf(manager).generation(), incoming, revBefore + 1);
                            LyricSession after = (LyricSession) getField(manager, "session");
                            assertNotNull(tag + " session must remain non-null after publishSeat", after);
                            if (expectedSameDigest) {
                                assertEquals(tag + " equal-digest reseat must keep sourceRevision",
                                        revBefore, after.identity.sourceRevision);
                            } else {
                                assertEquals(tag + " different-digest reseat must increment sourceRevision",
                                        revBefore + 1, after.identity.sourceRevision);
                                assertEquals(tag + " different-digest reseat must reset sound layer",
                                        LayerStatus.ABSENT, after.sound.status);
                                assertEquals(tag + " different-digest reseat must reset meaning layer",
                                        LayerStatus.ABSENT, after.meaning.status);
                            }
                        }
                        break;
                    }
                    case 7: { // Layer-scoped restore
                        LayerKind layer = rng.nextBoolean() ? LayerKind.SOUND : LayerKind.MEANING;
                        LayerKind sibling = layer == LayerKind.SOUND ? LayerKind.MEANING : LayerKind.SOUND;
                        LyricSession before = (LyricSession) getField(manager, "session");
                        LayerState siblingBefore = before == null ? null : before.layer(sibling);
                        manager.restoreLayer(layer);
                        LyricSession after = (LyricSession) getField(manager, "session");
                        if (before != null && after != null) {
                            assertEquals(tag + " restoreLayer must not disturb sibling layer",
                                    siblingBefore.status, after.layer(sibling).status);
                            assertEquals(tag + " restoreLayer must not drop sibling artifact",
                                    siblingBefore.artifact, after.layer(sibling).artifact);
                        }
                        break;
                    }
                    case 8: { // Flip a preference (translation target or romanization toggle)
                        String[] targets = {"en", "vi", "fr", "de"};
                        context.getSharedPreferences(SpotifyPlusConfig.PREFS_NAME, Context.MODE_PRIVATE)
                                .edit()
                                .putString(Settings.TRANSLATION_TARGET.key, targets[rng.nextInt(targets.length)])
                                .putBoolean(Settings.TRANSLITERATION_ENABLED.key, rng.nextBoolean())
                                .apply();
                        break;
                    }
                    case 9: { // Terminate SpicyOrg access mid-flight
                        if (!SpicyOrgAccessState.isTerminated(context)) {
                            SpicyOrgAccessState.RequestTicket token = SpicyOrgAccessState.beginRequest(context, false);
                            SpicyOrgAccessState.terminate(context, token, "key_revoked");
                            LyricsDocument liveDoc = (LyricsDocument) getField(manager, "document");
                            if (liveDoc != null && SpicyOrgPolicy.isRestricted(liveDoc)) {
                                assertNull(tag + " publishedProjection must withhold restricted org doc once terminated",
                                        invokePublishedProjection(manager, liveDoc));
                            }
                        }
                        break;
                    }
                    default:
                        break;
                }

                assertSessionStateCoherent(manager, context, tag);
            }

            scheduler.drainAll();
            assertSessionStateCoherent(manager, context, "seed=" + seed + " final");
        } finally {
            sub.close();
        }
    }

    private void runCatalogPlannerSimulation(long seed) {
        Random rng = new Random(seed);
        String trackId = "track-" + seed;
        long nowMs = System.currentTimeMillis();

        // Build random enabled order & ranking mode.
        List<SourceId> enabled = new ArrayList<>(Arrays.asList(AUTO_SOURCES));
        Collections.shuffle(enabled, rng);
        int keep = 1 + rng.nextInt(enabled.size());
        enabled = new ArrayList<>(enabled.subList(0, keep));
        boolean sourceOrderMode = rng.nextBoolean();
        CatalogPolicy policy = new CatalogPolicy(enabled, sourceOrderMode);

        // Create 1..5 candidates from distinct sources so ranking is unambiguous.
        List<CatalogCandidate> baseCandidates = new ArrayList<>();
        Map<SourceId, ProviderRecord> providers = new EnumMap<>(SourceId.class);
        List<SourceId> pool = new ArrayList<>(Arrays.asList(AUTO_SOURCES));
        Collections.shuffle(pool, rng);
        int candidateCount = 1 + rng.nextInt(Math.min(5, pool.size()));
        for (int i = 0; i < candidateCount; i++) {
            SourceId src = pool.get(i);
            TimingLevel timing = TimingLevel.values()[rng.nextInt(TimingLevel.values().length)];
            // Avoid expired SPICY_ORG candidates unless testing expiry explicitly.
            long fetchedAt = src == SourceId.SPICY_ORG
                    ? nowMs - (rng.nextBoolean() ? 1_000L : SpicyOrgPolicy.REFRESH_AFTER_MS + 60_000L)
                    : nowMs - 60_000L;
            String digest = "digest-" + src.id + "-" + seed;
            CatalogCandidate cand = new CatalogCandidate(
                    CatalogCandidate.stableId(trackId, src, "item-" + i, digest),
                    trackId, src, "item-" + i, MatchMethod.EXACT_SPOTIFY_ID, 1.0,
                    0L, timing, true, true, false, false, false, false, false,
                    digest, "", "", null, 1, 1, fetchedAt);
            baseCandidates.add(cand);
            providers.put(src, new ProviderRecord(src, ProviderStatus.AVAILABLE,
                    fetchedAt, fetchedAt, fetchedAt, 1));
        }

        // Invariant 1: Under Auto (no incumbent), winner is identical for any candidate arrival permutation.
        List<CatalogCandidate> permA = new ArrayList<>(baseCandidates);
        List<CatalogCandidate> permB = new ArrayList<>(baseCandidates);
        Collections.shuffle(permA, rng);
        Collections.shuffle(permB, rng);
        CatalogState stateA = new CatalogState(trackId, permA, providers, CatalogSelection.auto(trackId), null, true);
        CatalogState stateB = new CatalogState(trackId, permB, providers, CatalogSelection.auto(trackId), null, true);
        CatalogResolver.Resolution resA = CatalogDecisions.render(stateA, policy);
        CatalogResolver.Resolution resB = CatalogDecisions.render(stateB, policy);
        String winnerA = resA.winner == null ? "" : resA.winner.candidateId;
        String winnerB = resB.winner == null ? "" : resB.winner.candidateId;
        assertEquals("seed=" + seed + " Auto winner depended on candidate arrival order", winnerA, winnerB);

        // Invariant 2: A manual pin retains authority even when its source is disabled or lower priority,
        // and AcquisitionPlanner stops fallback probes (allowing only org-refresh for overdue enabled org pin).
        CatalogCandidate pinned = baseCandidates.get(rng.nextInt(baseCandidates.size()));
        CatalogSelection manualSel = new CatalogSelection(trackId, SelectionMode.MANUAL,
                pinned.candidateId, pinned.sourceId, pinned.providerItemId, pinned.canonicalDigest);
        CatalogState pinnedState = new CatalogState(trackId, permA, providers, manualSel, null, true);
        CatalogResolver.Resolution pinnedRes = CatalogDecisions.render(pinnedState, policy);
        assertNotNull("seed=" + seed + " manual pin returned null winner", pinnedRes.winner);
        assertEquals("seed=" + seed + " manual pin did not hold its candidate",
                pinned.candidateId, pinnedRes.winner.candidateId);
        assertFalse("seed=" + seed + " valid manual pin marked temporary", pinnedRes.temporary);

        AcquisitionPlanner.Plan pinnedPlan = AcquisitionPlanner.plan(
                pinnedState, policy, pinnedRes, nowMs, true, Collections.emptySet());
        if (pinnedPlan.fetches()) {
            assertEquals("seed=" + seed + " manual pin must only ever trigger org-refresh",
                    "org-refresh", pinnedPlan.reason);
            assertEquals("seed=" + seed + " org-refresh must ask only SPICY_ORG",
                    Collections.singletonList(SourceId.SPICY_ORG), pinnedPlan.scope.sources);
        }

        // Invariant 3: Automatic acquisition asks at most one source per plan step and respects attempted set.
        Set<SourceId> attempted = EnumSet.noneOf(SourceId.class);
        CatalogState walkState = stateA;
        CatalogResolver.Resolution walkRes = resA;
        for (int step = 0; step <= AUTO_SOURCES.length + 2; step++) {
            AcquisitionPlanner.Plan plan = AcquisitionPlanner.plan(
                    walkState, policy, walkRes, nowMs, !attempted.contains(SourceId.SPOTIFY_NATIVE), attempted);
            if (!plan.fetches()) break;
            assertEquals("seed=" + seed + " auto plan must ask exactly one source per step",
                    1, plan.scope.sources.size());
            SourceId asked = plan.scope.sources.get(0);
            if (asked == SourceId.SPOTIFY_NATIVE) {
                assertFalse("seed=" + seed + " SPOTIFY_NATIVE asked after localTried",
                        attempted.contains(SourceId.SPOTIFY_NATIVE));
            }
            attempted.add(asked);
            // Record NOT_FOUND so the walk converges strictly within AUTO_SOURCES.length steps.
            Map<SourceId, ProviderRecord> nextProviders = new EnumMap<>(walkState.providers);
            nextProviders.put(asked, new ProviderRecord(asked, ProviderStatus.NOT_FOUND,
                    nowMs, nowMs, 0L, 1));
            walkState = new CatalogState(trackId, walkState.candidates, nextProviders,
                    walkState.selection, walkState.rejections, true);
            if (step == AUTO_SOURCES.length + 1) {
                fail("seed=" + seed + " AcquisitionPlanner failed to converge after recording outcomes");
            }
        }
    }

    private void runPrefetchSimulation(long seed) throws Exception {
        Random rng = new Random(seed);
        FakeAndroidContext context = new FakeAndroidContext();
        LyricsSessionManager live = newPrefetchTestManager(context);
        LyricsSessionManager bg = newPrefetchTestManager(context);

        String liveUri = TRACK_URIS[rng.nextInt(TRACK_URIS.length)];
        boolean sameTrack = rng.nextBoolean();
        boolean sameDigest = rng.nextBoolean();
        boolean sameSettings = rng.nextBoolean();
        boolean bgRunning = rng.nextBoolean();
        boolean bgFailed = rng.nextBoolean();
        LayerKind bgLayer = rng.nextBoolean() ? LayerKind.MEANING : LayerKind.SOUND;

        String bgUri = sameTrack ? liveUri : TRACK_URIS[(Arrays.asList(TRACK_URIS).indexOf(liveUri) + 1) % TRACK_URIS.length];
        String liveText = "歌詞 " + seed;
        String bgText = sameDigest ? liveText : "別の歌詞 " + seed;

        installSimpleSession(live, liveUri, liveText, null, false, false);
        LyricsDocument bgDoc = installSimpleSession(bg, bgUri, bgText, bgLayer, bgRunning, bgFailed);

        String inputs = sameSettings
                ? (String) invokeNoArg(bg, "preparationInputs", LyricsDocument.class, bgDoc)
                : "stale-settings-" + seed;
        setField(bg, "preparedInputs", inputs);
        setField(live, "upcoming", bg);

        @SuppressWarnings("unchecked")
        EnumSet<LayerKind> reused = (EnumSet<LayerKind>) invokeZeroArg(live, "joinUpcoming");
        boolean shouldMatch = sameTrack && sameDigest && sameSettings && !bgFailed;
        String tag = "seed=" + seed + " sameTrack=" + sameTrack + " sameDigest=" + sameDigest
                + " sameSettings=" + sameSettings + " bgRunning=" + bgRunning + " bgFailed=" + bgFailed;

        if (shouldMatch) {
            assertEquals(tag + " expected matching prefetch layer to be reused",
                    EnumSet.of(bgLayer), reused);
            LyricSession liveSession = (LyricSession) getField(live, "session");
            assertEquals(tag + " joined layer status mismatch",
                    bgRunning ? LayerStatus.PROCESSING : LayerStatus.READY,
                    liveSession.layer(bgLayer).status);
            if (!bgRunning) {
                assertNull(tag + " completed prefetch must detach upcoming after join",
                        getField(live, "upcoming"));
            }
        } else {
            assertEquals(tag + " mismatched or failed prefetch must never be reused",
                    EnumSet.noneOf(LayerKind.class), reused);
            if (sameTrack) {
                assertNull(tag + " same-track rejected prefetch must clear upcoming",
                        getField(live, "upcoming"));
            } else {
                assertNotNull(tag + " different-track queued prefetch must remain alive",
                        getField(live, "upcoming"));
            }
        }
    }

    private static void assertSessionStateCoherent(LyricsSessionManager manager, Context context,
                                                   String tag) throws Exception {
        LyricsDocument doc = (LyricsDocument) getField(manager, "document");
        LyricSession session = (LyricSession) getField(manager, "session");
        boolean awaitingDetection = (Boolean) getField(manager, "awaitingDetection");
        if (doc != null && session != null) {
            if (!awaitingDetection && (session.sound.status == LayerStatus.PROCESSING
                    || session.meaning.status == LayerStatus.PROCESSING)) {
                assertEquals(tag + " romanizationPending diverged from session.sound.status",
                        session.sound.status == LayerStatus.PROCESSING, doc.romanizationPending);
                assertEquals(tag + " translationPending diverged from session.meaning.status",
                        session.meaning.status == LayerStatus.PROCESSING, doc.translationPending);
                assertEquals(tag + " processingPending diverged",
                        doc.romanizationPending || doc.translationPending, doc.processingPending);
            }
            LyricsDocument projection = invokePublishedProjection(manager, doc);
            if (SpicyOrgPolicy.isRestricted(doc) && SpicyOrgAccessState.isTerminated(context)) {
                assertNull(tag + " terminated org doc leaked through publishedProjection", projection);
            } else {
                assertNotNull(tag + " publishedProjection unexpectedly null", projection);
                assertEquals(tag + " projected line count mismatch",
                        doc.lines.size(), projection.lines.size());
                assertEquals(tag + " projected romanizationPending diverged from session",
                        session.sound.status == LayerStatus.PROCESSING, projection.romanizationPending);
                assertEquals(tag + " projected translationPending diverged from session",
                        session.meaning.status == LayerStatus.PROCESSING, projection.translationPending);
            }
        }
    }

    private static long[] seeds(int count, long baseSeed) {
        String single = System.getProperty("spicy.dst.seed");
        if (single != null && !single.trim().isEmpty()) {
            return new long[]{Long.parseLong(single.trim())};
        }
        long[] out = new long[count];
        for (int i = 0; i < count; i++) out[i] = baseSeed + i;
        return out;
    }

    private static LyricsDetectionSession newDetectionSession(Context context, SimScheduler scheduler)
            throws Exception {
        Constructor<LyricsDetectionSession> ctor = LyricsDetectionSession.class.getDeclaredConstructor(
                Context.class, java.util.concurrent.Executor.class,
                LyricsDetectionSession.Poster.class, LyricsDetectionSession.Cache.class,
                LyricsDetectionSession.Detector.class);
        ctor.setAccessible(true);
        Map<String, DetectionArtifact> cacheMap = new HashMap<>();
        return ctor.newInstance(context,
                (java.util.concurrent.Executor) scheduler::enqueue,
                (LyricsDetectionSession.Poster) scheduler::enqueue,
                new LyricsDetectionSession.Cache() {
                    @Override public DetectionArtifact restore(Context c, CanonicalBase b) {
                        return cacheMap.get(b.digest);
                    }
                    @Override public boolean save(Context c, CanonicalBase b, DetectionArtifact a) {
                        cacheMap.put(b.digest, a);
                        return true;
                    }
                },
                (LyricsDetectionSession.Detector) (text, known) -> DetectionResult.detected(
                        "", text, ScriptClassifier.ScriptClass.LATIN, "en", 0.95d));
    }

    private static LyricsSessionManager newPrefetchTestManager(Context context) {
        LyricsSecondaryProcessor processor = new LyricsSecondaryProcessor(
                context, new OkHttpClient(), null, null, null, null, null, 1);
        LyricsSecondaryProcessingSession processing = new LyricsSecondaryProcessingSession(
                context, SpotifyPlusConfig.from(context), processor, 1, "[DST-Prefetch]");
        return new LyricsSessionManager(context, null, processing,
                new LyricsDetectionSession(context, Runnable::run), () -> 1000L);
    }

    private static LyricsDocument installSimpleSession(LyricsSessionManager owner, String uri,
                                                       String text, LayerKind layer,
                                                       boolean running, boolean failed) throws Exception {
        LyricsSessionPolicy policy = policyOf(owner);
        policy.adoptTrack(uri);
        LyricsDocument doc = new LyricsDocument();
        doc.trackId = uri;
        doc.language = "ja";
        doc.romanizationPending = true;
        doc.translationPending = true;
        LyricsLine line = new LyricsLine();
        line.text = text;
        doc.lines.add(line);
        CanonicalBase base = CanonicalBase.fromDocument(uri, doc);
        LyricSession session = LyricSession.of(base, policy.generation(), 1);
        if (layer != null) {
            if (failed) {
                session = session.withLayer(layer, session.layer(layer).settled(
                        null, LayerFailure.of(LayerFailure.Reason.SERVER_ERROR)));
            } else if (running) {
                session = session.withLayer(layer, session.layer(layer).processing(
                        LayerAuthority.AI, "cfg", "", "prefetch-run"));
            } else if (layer == LayerKind.MEANING) {
                MeaningArtifact art = new MeaningArtifact(base.digest, "cfg",
                        new LayerProvenance(LayerAuthority.AI, "dst", "cfg", 1L),
                        Collections.singletonList(new MeaningEntry(base.rowAt(0).rowId, "Translated", "en")),
                        false);
                session = session.withLayer(layer, session.meaning.withArtifact(LayerStatus.READY, art, ""));
            } else {
                SoundArtifact art = new SoundArtifact(base.digest, "cfg",
                        new LayerProvenance(LayerAuthority.DETERMINISTIC, "dst", "cfg", 1L),
                        Collections.singletonList(SoundEntry.line(base.rowAt(0).rowId, "kashi", "")),
                        false);
                session = session.withLayer(layer, session.sound.withArtifact(LayerStatus.READY, art, ""));
            }
        }
        setField(owner, "document", doc);
        setField(owner, "canonicalSource", LyricsDocument.copyOf(doc));
        setField(owner, "session", session);
        return doc;
    }

    private static SpotifyTrack track(String uri, String title) {
        return new SpotifyTrack(title, "Artist", "Album", uri, 0L, "", 0L, "", 180_000L, false);
    }

    private static LyricsDocument buildDoc(String uri, int variantIdx, boolean spicyOrg,
                                           String candidateId) {
        LyricsDocument doc = new LyricsDocument();
        doc.trackId = uri;
        doc.type = "Line";
        doc.language = variantIdx == 1 ? "en" : "ja";
        doc.provider = spicyOrg ? "Spicy Lyrics" : "Apple Music";
        doc.fetchSource = spicyOrg ? "spicy_org" : "apple_music";
        doc.spicyOrgSource = spicyOrg ? "spicy_lyrics" : "";
        doc.spicyOrgFetchedAtMs = spicyOrg ? System.currentTimeMillis() : 0L;
        doc.catalogCandidateId = candidateId;
        String[] rows = LYRIC_VARIANTS[variantIdx];
        for (int i = 0; i < rows.length; i++) {
            LyricsLine line = new LyricsLine();
            line.text = rows[i];
            line.startMs = (i + 1) * 4_000L;
            line.endMs = (i + 1) * 4_000L + 3_000L;
            doc.lines.add(line);
        }
        return doc;
    }

    private static void adoptTrackForTest(LyricsSessionManager manager, SpotifyTrack next)
            throws Exception {
        // Mirror adoptTrack state reset without touching NativeRuntime.LYRICS_IO or null hook.
        LyricsSessionPolicy policy = policyOf(manager);
        policy.adoptTrack(next.uri);
        setField(manager, "track", null);
        setField(manager, "loadingUri", "");
        setField(manager, "canonicalLoadingUri", "");
        setField(manager, "document", null);
        setField(manager, "canonicalSource", null);
        setField(manager, "session", null);
        setField(manager, "status", "loading");
        setField(manager, "displayedCandidateId", "");
        setField(manager, "pendingPlan", null);
        setField(manager, "replanning", false);
        setField(manager, "localTried", false);
        setField(manager, "autoAttempts", 0);
        setField(manager, "noLyricsReason", "");
        setField(manager, "detectionArtifact", null);
        setField(manager, "awaitingDetection", false);
        Method notify = LyricsSessionManager.class.getDeclaredMethod("notifyState",
                LyricsSessionManager.Snapshot.class);
        notify.setAccessible(true);
        notify.invoke(manager, new LyricsSessionManager.Snapshot(
                next, policy.trackUri(), policy.generation(), "loading", false, 0L, 0L, 0d));
    }

    private static LyricsCatalog.View newCatalogView(String trackUri, CatalogPolicy policy,
                                                     LyricsDocument doc, int revision,
                                                     boolean readable, boolean durable, long sequence)
            throws Exception {
        Constructor<?> ctor = LyricsCatalog.View.class.getDeclaredConstructor(
                String.class, CatalogState.class, CatalogPolicy.class, CatalogResolver.Resolution.class,
                AcquisitionPlanner.Plan.class, LyricsDocument.class, int.class,
                boolean.class, boolean.class, long.class, String.class);
        ctor.setAccessible(true);
        String bare = trackUri.substring(trackUri.lastIndexOf(':') + 1);
        return (LyricsCatalog.View) ctor.newInstance(
                bare, CatalogState.empty(bare), policy, null, null, doc, revision,
                readable, durable, sequence, "");
    }

    private static LyricsSessionPolicy policyOf(LyricsSessionManager manager) throws Exception {
        return (LyricsSessionPolicy) getField(manager, "policy");
    }

    private static Object getField(LyricsSessionManager manager, String name) throws Exception {
        Field f = LyricsSessionManager.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(manager);
    }

    private static long getLongField(LyricsSessionManager manager, String name) throws Exception {
        Field f = LyricsSessionManager.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.getLong(manager);
    }

    private static void setField(LyricsSessionManager manager, String name, Object value) throws Exception {
        Field f = LyricsSessionManager.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(manager, value);
    }

    private static Object invokeZeroArg(LyricsSessionManager owner, String name) throws Exception {
        Method m = LyricsSessionManager.class.getDeclaredMethod(name);
        m.setAccessible(true);
        return m.invoke(owner);
    }

    private static Object invokeNoArg(LyricsSessionManager owner, String name,
                                      Class<?> paramType, Object arg) throws Exception {
        Method m = LyricsSessionManager.class.getDeclaredMethod(name, paramType);
        m.setAccessible(true);
        return m.invoke(owner, arg);
    }

    private static void invokeAdoptLayerArtifact(LyricsSessionManager manager, LayerKind layer,
                                                 com.eza.spicyex.lyrics.session.DerivedLayerArtifact artifact,
                                                 LayerFailure failure, LyricsDocument doc, int generation)
            throws Exception {
        Method m = LyricsSessionManager.class.getDeclaredMethod("adoptLayerArtifact",
                LayerKind.class, com.eza.spicyex.lyrics.session.DerivedLayerArtifact.class,
                LayerFailure.class, LyricsDocument.class, int.class);
        m.setAccessible(true);
        m.invoke(manager, layer, artifact, failure, doc, generation);
    }

    private static void invokePublishSeat(LyricsSessionManager manager, SpotifyTrack track,
                                          String uri, int generation, LyricsDocument doc, int revision)
            throws Exception {
        Method m = LyricsSessionManager.class.getDeclaredMethod("publishSeat",
                SpotifyTrack.class, String.class, int.class, LyricsDocument.class, int.class);
        m.setAccessible(true);
        m.invoke(manager, track, uri, generation, doc, revision);
    }

    private static LyricsDocument invokePublishedProjection(LyricsSessionManager manager,
                                                            LyricsDocument doc) throws Exception {
        Method m = LyricsSessionManager.class.getDeclaredMethod("publishedProjection", LyricsDocument.class);
        m.setAccessible(true);
        return (LyricsDocument) m.invoke(manager, doc);
    }

    private static final class SimClock {
        long now;

        SimClock(long startMs) {
            this.now = startMs;
        }

        void advance(long deltaMs) {
            now += deltaMs;
        }

        long nowMs() {
            return now;
        }
    }

    private static final class SimScheduler {
        private final Random rng;
        private final List<Runnable> queue = new ArrayList<>();

        SimScheduler(Random rng) {
            this.rng = rng;
        }

        void enqueue(Runnable task) {
            if (task != null) queue.add(task);
        }

        void runRandom(int maxTasks) {
            for (int i = 0; i < maxTasks && !queue.isEmpty(); i++) {
                Runnable next = queue.remove(rng.nextInt(queue.size()));
                next.run();
            }
        }

        void drainAll() {
            int guard = 0;
            while (!queue.isEmpty() && guard++ < 500) {
                Runnable next = queue.remove(rng.nextInt(queue.size()));
                next.run();
            }
        }
    }

    private static final class SimExecutorService extends AbstractExecutorService {
        private final SimScheduler scheduler;
        private volatile boolean shutdown;

        SimExecutorService(SimScheduler scheduler) {
            this.scheduler = scheduler;
        }

        @Override public void shutdown() { shutdown = true; }
        @Override public List<Runnable> shutdownNow() { shutdown = true; return Collections.emptyList(); }
        @Override public boolean isShutdown() { return shutdown; }
        @Override public boolean isTerminated() { return shutdown; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
        @Override public void execute(Runnable command) {
            if (!shutdown) scheduler.enqueue(command);
        }
    }

    private static final class PendingProviderEvent {
        final SpotifyTrack track;
        final String uri;
        final int generation;
        final LyricsDocument doc;
        final LyricsCatalog.View view;

        PendingProviderEvent(SpotifyTrack track, String uri, int generation,
                             LyricsDocument doc, LyricsCatalog.View view) {
            this.track = track;
            this.uri = uri;
            this.generation = generation;
            this.doc = doc;
            this.view = view;
        }
    }

    private static final class PendingLayerEvent {
        final LayerKind layer;
        final LyricsDocument doc;
        final CanonicalBase base;
        final int generation;
        final boolean fail;

        PendingLayerEvent(LayerKind layer, LyricsDocument doc, CanonicalBase base,
                          int generation, boolean fail) {
            this.layer = layer;
            this.doc = doc;
            this.base = base;
            this.generation = generation;
            this.fail = fail;
        }
    }
}
