package com.eza.spicyex.lyrics.catalog;

import java.util.List;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.eza.spicyex.lyrics.catalog.AcquisitionPlanner.Plan;
import com.eza.spicyex.lyrics.catalog.CatalogSource.MatchMethod;
import com.eza.spicyex.lyrics.catalog.CatalogSource.ProviderStatus;
import com.eza.spicyex.lyrics.catalog.CatalogSource.SelectionMode;
import com.eza.spicyex.lyrics.catalog.CatalogSource.SourceId;
import com.eza.spicyex.lyrics.catalog.CatalogSource.TimingLevel;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/** Acquisition corpus: which sources a visit asks, given only stored state, policy, and clock. */
public class AcquisitionPlannerTest {
    private static final String TRACK = "track1";
    private static final long NOW = 10L * 24L * 60L * 60L * 1000L;
    private static final CatalogPolicy AUTO = new CatalogPolicy(Arrays.asList(SourceId.APPLE,
            SourceId.SPOTIFY_NATIVE, SourceId.AMLL, SourceId.LRCLIB, SourceId.QQ), false);

    private static CatalogState state(CatalogSelection selection, ProviderRecord... records) {
        return state(selection, new CatalogCandidate[0], records);
    }

    private static CatalogState state(CatalogSelection selection, CatalogCandidate[] candidates,
                                      ProviderRecord... records) {
        Map<SourceId, ProviderRecord> providers = new EnumMap<>(SourceId.class);
        for (ProviderRecord record : records) providers.put(record.source, record);
        return new CatalogState(TRACK, Arrays.asList(candidates), providers, selection, null, true);
    }

    private static ProviderRecord answered(SourceId source, ProviderStatus status, long atMs,
                                           int attempts) {
        return new ProviderRecord(source, status, atMs, atMs, 0L, attempts);
    }

    private static Plan plan(CatalogState state, CatalogPolicy policy, boolean includeLocal) {
        return AcquisitionPlanner.plan(state, policy, CatalogDecisions.render(state, policy), NOW,
                includeLocal);
    }

    private static CatalogCandidate seated(SourceId source, TimingLevel timing) {
        return CatalogDecisionsTest.cand(source, "item", "d-" + source.id, timing,
                MatchMethod.EXACT_SPOTIFY_ID);
    }

    private static CatalogSelection auto(CatalogCandidate candidate) {
        return new CatalogSelection(TRACK, SelectionMode.AUTO, candidate.candidateId,
                candidate.sourceId, candidate.providerItemId, candidate.canonicalDigest);
    }

    @Test
    public void anUnseenTrackAsksEveryAutomaticSourceButNotExplicitCheckSources() {
        Plan plan = plan(CatalogState.empty(TRACK), AUTO, true);

        assertTrue(plan.fetches());
        assertEquals("no-seat", plan.reason);
        assertEquals(Arrays.asList(SourceId.APPLE, SourceId.SPOTIFY_NATIVE, SourceId.AMLL,
                SourceId.LRCLIB), plan.scope.sources);
        assertFalse(plan.scope.allows(SourceId.QQ));
    }

    @Test
    public void aWordOrSyllableSeatRevisitCostsNoRequest() {
        for (TimingLevel good : new TimingLevel[]{TimingLevel.SYLLABLE, TimingLevel.WORD}) {
            CatalogCandidate seat = seated(SourceId.APPLE, good);
            Plan plan = plan(state(auto(seat), new CatalogCandidate[]{seat}), AUTO, true);

            assertFalse(good.name(), plan.fetches());
            assertEquals(good.name(), "final-seat", plan.reason);
            assertEquals(good.name(), 0L, plan.retryAtMs);
        }
    }

    @Test
    public void aLineSeatKeepsSearchingBecauseTheThresholdIsWordNotLine() {
        CatalogCandidate line = seated(SourceId.SPOTIFY_NATIVE, TimingLevel.LINE);
        Plan plan = plan(state(auto(line), new CatalogCandidate[]{line}), AUTO, true);

        assertTrue(plan.fetches());
        assertEquals("upgrade-probe", plan.reason);
        // Every automatic source is reachable again, so a better document can still be found.
        assertTrue(plan.scope.allows(SourceId.APPLE));
        assertTrue(plan.scope.allows(SourceId.AMLL));
        assertTrue(plan.scope.allows(SourceId.LRCLIB));
    }

    @Test
    public void aLineSeatSettlesOntoTheUpgradeHorizonInsteadOfHammeringSources() {
        CatalogCandidate line = seated(SourceId.SPOTIFY_NATIVE, TimingLevel.LINE);
        long justAsked = NOW - 60_000L;
        Plan after = plan(state(auto(line), new CatalogCandidate[]{line},
                answered(SourceId.APPLE, ProviderStatus.NOT_FOUND, justAsked, 1),
                answered(SourceId.AMLL, ProviderStatus.AVAILABLE, justAsked, 1),
                answered(SourceId.LRCLIB, ProviderStatus.NOT_FOUND, justAsked, 1)), AUTO, true);

        assertFalse(after.fetches());
        assertEquals("upgrade-suppressed", after.reason);
        assertEquals(justAsked + AcquisitionPlanner.NOT_FOUND_RETRY_MS, after.retryAtMs);
    }

    @Test
    public void aManualPinIsFinalEvenWhenStatic() {
        CatalogCandidate plain = seated(SourceId.SPOTIFY_NATIVE, TimingLevel.UNSYNCED);
        CatalogSelection pin = new CatalogSelection(TRACK, SelectionMode.MANUAL,
                plain.candidateId, plain.sourceId, plain.providerItemId, plain.canonicalDigest);
        Plan plan = plan(state(pin, new CatalogCandidate[]{plain}), AUTO, true);

        assertFalse(plan.fetches());
        assertEquals("manual-pin", plan.reason);
    }

    @Test
    public void aStaticSeatProbesUncheckedNetworkSourcesThenWaitsTheUpgradeHorizon() {
        CatalogCandidate plain = seated(SourceId.SPOTIFY_NATIVE, TimingLevel.UNSYNCED);
        Plan first = plan(state(auto(plain), new CatalogCandidate[]{plain}), AUTO, true);
        assertTrue(first.fetches());
        assertEquals("upgrade-probe", first.reason);
        assertTrue(first.scope.allows(SourceId.APPLE));

        long justAsked = NOW - 60_000L;
        Plan after = plan(state(auto(plain), new CatalogCandidate[]{plain},
                answered(SourceId.APPLE, ProviderStatus.NOT_FOUND, justAsked, 1),
                answered(SourceId.AMLL, ProviderStatus.NOT_FOUND, justAsked, 1),
                answered(SourceId.LRCLIB, ProviderStatus.NOT_FOUND, justAsked, 1)), AUTO, true);
        assertFalse(after.fetches());
        assertEquals("upgrade-suppressed", after.reason);
        assertEquals(justAsked + AcquisitionPlanner.NOT_FOUND_RETRY_MS, after.retryAtMs);
    }

    @Test
    public void durableMissesAreNotReaskedWithinTheirHorizonButNativeStaysFree() {
        long justAsked = NOW - 1_000L;
        CatalogState missed = state(CatalogSelection.auto(TRACK),
                answered(SourceId.APPLE, ProviderStatus.NOT_FOUND, justAsked, 1),
                answered(SourceId.AMLL, ProviderStatus.NOT_FOUND, justAsked, 1),
                answered(SourceId.LRCLIB, ProviderStatus.NOT_FOUND, justAsked, 1));

        Plan visit = plan(missed, AUTO, true);
        assertTrue(visit.fetches());
        assertEquals(Collections.singletonList(SourceId.SPOTIFY_NATIVE), visit.scope.sources);

        Plan afterNative = plan(missed, AUTO, false);
        assertFalse(afterNative.fetches());
        assertEquals(justAsked + AcquisitionPlanner.NOT_FOUND_RETRY_MS, afterNative.retryAtMs);
    }

    @Test
    public void aDurableMissBecomesDueAgainAfterItsHorizon() {
        long longAgo = NOW - AcquisitionPlanner.NOT_FOUND_RETRY_MS - 1L;
        CatalogState missed = state(CatalogSelection.auto(TRACK),
                answered(SourceId.APPLE, ProviderStatus.NOT_FOUND, longAgo, 1));
        Plan plan = plan(missed, AUTO, false);

        assertTrue(plan.fetches());
        assertTrue(plan.scope.allows(SourceId.APPLE));
    }

    @Test
    public void transientFailuresBackOffExponentiallyUpToTheCap() {
        assertEquals(30_000L, AcquisitionPlanner.transientBackoffMs(1));
        assertEquals(60_000L, AcquisitionPlanner.transientBackoffMs(2));
        assertEquals(120_000L, AcquisitionPlanner.transientBackoffMs(3));
        assertEquals(AcquisitionPlanner.TRANSIENT_MAX_RETRY_MS,
                AcquisitionPlanner.transientBackoffMs(40));

        ProviderRecord failing = answered(SourceId.APPLE, ProviderStatus.TRANSIENT_ERROR,
                NOW - 10_000L, 1);
        assertEquals(NOW + 20_000L, AcquisitionPlanner.dueAtMs(failing, false));
    }

    @Test
    public void aSourceThatAlreadyDeliveredIsNeverReaskedWithoutASeatToUpgrade() {
        ProviderRecord delivered = answered(SourceId.APPLE, ProviderStatus.AVAILABLE, NOW, 0);
        assertEquals(Long.MAX_VALUE, AcquisitionPlanner.dueAtMs(delivered, false));
        assertEquals(NOW + AcquisitionPlanner.STATIC_UPGRADE_RETRY_MS,
                AcquisitionPlanner.dueAtMs(delivered, true));
    }

    @Test
    public void needsRefreshAndUncheckedAreDueImmediately() {
        assertEquals(0L, AcquisitionPlanner.dueAtMs(
                answered(SourceId.AMLL, ProviderStatus.NEEDS_REFRESH, NOW, 0), false));
        assertEquals(0L, AcquisitionPlanner.dueAtMs(ProviderRecord.notChecked(SourceId.AMLL), false));
    }

    @Test
    public void disabledSourcesAreNeverAskedAndAllDisabledAsksNothing() {
        CatalogPolicy onlyLrclib = new CatalogPolicy(Collections.singletonList(SourceId.LRCLIB), false);
        Plan plan = plan(CatalogState.empty(TRACK), onlyLrclib, true);
        assertEquals(Collections.singletonList(SourceId.LRCLIB), plan.scope.sources);

        Plan none = plan(CatalogState.empty(TRACK), new CatalogPolicy(null, false), true);
        assertFalse(none.fetches());
        assertEquals("all-sources-disabled", none.reason);
    }

    @Test
    public void sourceOrderAsksEveryEnabledSourceInTheOwnersOrder() {
        CatalogPolicy order = new CatalogPolicy(Arrays.asList(SourceId.QQ, SourceId.LRCLIB,
                SourceId.APPLE), true);
        Plan plan = plan(CatalogState.empty(TRACK), order, true);

        assertTrue(plan.scope.sourceOrderMode);
        assertEquals(Arrays.asList(SourceId.QQ, SourceId.LRCLIB, SourceId.APPLE),
                plan.scope.sources);
    }

    @Test
    public void ineligibleStoredKaraokeResultIsRecheckedVerbatimInSourceOrder() {
        CatalogPolicy off = new CatalogPolicy(Collections.singletonList(SourceId.QQ), true, false);
        CatalogCandidate karaoke = CatalogDecisionsTest.cand(SourceId.QQ, "song", "k1",
                TimingLevel.WORD, MatchMethod.KARAOKE_SUBSTITUTION);
        CatalogState stored = state(CatalogSelection.auto(TRACK),
                new CatalogCandidate[]{karaoke},
                answered(SourceId.QQ, ProviderStatus.AVAILABLE, NOW, 0));

        Plan retry = plan(stored, off, false);
        assertTrue(retry.fetches());
        assertEquals(Collections.singletonList(SourceId.QQ), retry.scope.sources);
        assertFalse(retry.scope.karaokeOriginalLyrics);
    }

    @Test
    public void requestScopeCarriesKaraokeSearchPolicyInItsIdentity() {
        CatalogPolicy on = new CatalogPolicy(Arrays.asList(SourceId.QQ), true, true);
        Plan refresh = AcquisitionPlanner.refreshAll(on);

        assertTrue(refresh.scope.karaokeOriginalLyrics);
        assertTrue(refresh.scope.key().contains("karaoke-original"));
        assertFalse(refresh.scope.key().equals(
                new AcquisitionScope(refresh.scope.sources, true, false).key()));
    }

    @Test
    public void anOwnerRefreshAsksEverySourceRegardlessOfStoredOutcomes() {
        Plan refresh = AcquisitionPlanner.refreshAll(AUTO);
        assertTrue(refresh.fetches());
        assertEquals(4, refresh.scope.sources.size());
    }

    @Test
    public void theOwnerClimbForcesTheSequentialPathInBuiltInOrder() {
        Plan ordered = AcquisitionPlanner.refreshAllInOrder(AUTO);

        assertTrue(ordered.fetches());
        assertEquals("owner-refresh-ordered", ordered.reason);
        // Sequential, not the racing chain the automatic visit uses.
        assertTrue(ordered.scope.sourceOrderMode);
        assertEquals(Arrays.asList(SourceId.APPLE, SourceId.SPOTIFY_NATIVE, SourceId.AMLL,
                SourceId.LRCLIB), ordered.scope.sources);
        // The plain owner refresh must keep the automatic path's own mode.
        assertFalse(AcquisitionPlanner.refreshAll(AUTO).scope.sourceOrderMode);
    }

    /**
     * The ordered walk is a preference walk, not a quality escalation. Guard the distinction so it
     * is not quietly advertised as an upgrade path: the sequential fetch recurses on failure and
     * stops at the first source that returns any lyrics, whatever its timing.
     */
    @Test
    public void theOrderedWalkStopsOnAnyLyricsNotOnFinalQuality() {
        List<SourceId> order = AcquisitionPlanner.refreshAllInOrder(AUTO).scope.sources;
        // AMLL sits after Apple, so an Apple success ends the walk before AMLL is ever asked.
        assertTrue(order.indexOf(SourceId.AMLL) > order.indexOf(SourceId.APPLE));
        // Escalating a line-timed seat is the automatic visit's job, and only below WORD.
        assertFalse(TimingLevel.LINE.isFinalQuality());
        assertTrue(TimingLevel.WORD.isFinalQuality());
    }

    @Test
    public void theFinalQualityThresholdIsWordNotLine() {
        assertTrue(TimingLevel.SYLLABLE.isFinalQuality());
        assertTrue(TimingLevel.WORD.isFinalQuality());
        assertFalse(TimingLevel.LINE.isFinalQuality());
        assertFalse(TimingLevel.UNSYNCED.isFinalQuality());
    }
}
