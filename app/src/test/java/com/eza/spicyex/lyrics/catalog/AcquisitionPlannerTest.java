package com.eza.spicyex.lyrics.catalog;

import static org.junit.Assert.*;

import com.eza.spicyex.lyrics.catalog.AcquisitionPlanner.Plan;
import com.eza.spicyex.lyrics.catalog.CatalogSource.*;
import org.junit.Test;
import java.util.*;

/** Provider outcomes drive sequential fallback; content completeness gates donor acquisition. */
public class AcquisitionPlannerTest {
    private static final String TRACK = "track1";
    private static final long NOW = System.currentTimeMillis();
    private static final CatalogPolicy AUTO = new CatalogPolicy(Arrays.asList(SourceId.SPICY_ORG,
            SourceId.APPLE, SourceId.SPOTIFY_NATIVE, SourceId.LRCLIB, SourceId.AMLL,
            SourceId.QQ, SourceId.NETEASE), false);

    private static CatalogCandidate candidate(SourceId source, TimingLevel timing, boolean complete) {
        return candidate(source, timing, complete, NOW);
    }

    private static CatalogCandidate candidate(SourceId source, TimingLevel timing, boolean complete,
                                               long fetchedAt) {
        return new CatalogCandidate(source.id, TRACK, source, "item", MatchMethod.EXACT_SPOTIFY_ID,
                .9, 0, timing, complete, true, false, false, false, false, false,
                source.id, "", "[]", null, 1, 1, fetchedAt);
    }

    private static CatalogState state(CatalogCandidate seat, ProviderRecord... records) {
        Map<SourceId, ProviderRecord> providers = new EnumMap<>(SourceId.class);
        for (ProviderRecord record : records) providers.put(record.source, record);
        return new CatalogState(TRACK, seat == null ? Collections.emptyList()
                : Collections.singletonList(seat), providers, CatalogSelection.auto(TRACK), null, true);
    }

    private static ProviderRecord answered(SourceId source, ProviderStatus status, long at, int attempts) {
        return new ProviderRecord(source, status, at, at, 0, attempts);
    }

    private static Plan plan(CatalogState state, CatalogPolicy policy, boolean local) {
        return AcquisitionPlanner.plan(state, policy, CatalogDecisions.render(state, policy), NOW, local);
    }

    @Test public void unseenTrackAsksOnlyRouterThenPrimaryAfterMiss() {
        Plan first = plan(state(null), AUTO, true);
        assertEquals(Collections.singletonList(SourceId.SPICY_ORG), first.scope.sources);
        assertTrue(first.scope.sourceOrderMode);
        Plan backup = plan(state(null, answered(SourceId.SPICY_ORG, ProviderStatus.NOT_FOUND,
                NOW, 1)), AUTO, true);
        assertEquals(Collections.singletonList(SourceId.APPLE), backup.scope.sources);
    }

    @Test public void completeOrgLineAndStaticSuppressAllOtherSourcesFreshOrCached() {
        for (TimingLevel timing : TimingLevel.values()) {
            CatalogCandidate org = candidate(SourceId.SPICY_ORG, timing, true);
            assertFalse(plan(state(org), AUTO, true).fetches());
            assertFalse(plan(state(org, answered(SourceId.SPICY_ORG, ProviderStatus.AVAILABLE,
                    NOW, 0)), AUTO, false).fetches());
        }
    }

    @Test public void lowerCachedSeatCannotSuppressEarlierEligiblePrimary() {
        CatalogCandidate apple = candidate(SourceId.APPLE, TimingLevel.SYLLABLE, true);
        assertEquals(Collections.singletonList(SourceId.SPICY_ORG),
                plan(state(apple), AUTO, true).scope.sources);
        CatalogCandidate fallback = candidate(SourceId.QQ, TimingLevel.WORD, true);
        assertEquals(Collections.singletonList(SourceId.SPICY_ORG),
                plan(state(fallback), AUTO, true).scope.sources);
    }

    @Test public void completeBackupStopsOnceEarlierRouterUnavailable() {
        CatalogCandidate apple = candidate(SourceId.APPLE, TimingLevel.UNSYNCED, true);
        Plan after = plan(state(apple, answered(SourceId.SPICY_ORG,
                ProviderStatus.TRANSIENT_ERROR, NOW, 1)), AUTO, true);
        assertFalse(after.fetches());
        assertEquals(NOW + AcquisitionPlanner.TRANSIENT_BASE_RETRY_MS, after.retryAtMs);
    }

    @Test public void syncUpgradeOnlyProbesEligibleDonorsAndNeverReasksAvailableDonor() {
        CatalogPolicy enabled = new CatalogPolicy(AUTO.enabledOrder, false, false, true);
        CatalogCandidate org = candidate(SourceId.SPICY_ORG, TimingLevel.LINE, true);
        assertFalse(plan(state(org), AUTO, true).fetches());
        assertEquals(Collections.singletonList(SourceId.QQ), plan(state(org), enabled, true).scope.sources);
        Plan next = plan(state(org, answered(SourceId.QQ, ProviderStatus.AVAILABLE, NOW, 0)), enabled, true);
        assertEquals(Collections.singletonList(SourceId.NETEASE), next.scope.sources);
        assertFalse(plan(state(org, answered(SourceId.QQ, ProviderStatus.AVAILABLE, NOW, 0),
                answered(SourceId.NETEASE, ProviderStatus.AVAILABLE, NOW, 0)), enabled, true).fetches());
    }

    @Test public void partialPrimaryStaysSeatedDuringOptInTimingUpgrade() {
        CatalogCandidate org = candidate(SourceId.SPICY_ORG, TimingLevel.WORD, false);
        CatalogState stored = state(org);
        assertFalse(plan(stored, AUTO, true).fetches());
        CatalogPolicy upgrade = new CatalogPolicy(AUTO.enabledOrder, false, false, true);
        Plan next = plan(stored, upgrade, true);
        assertEquals(Collections.singletonList(SourceId.QQ), next.scope.sources);
        assertEquals("sync-upgrade-probe", next.reason);
        assertSame(org, CatalogDecisions.render(stored, upgrade).winner);
    }

    @Test public void manualPinSuppressesAutomaticAcquisition() {
        CatalogCandidate apple = candidate(SourceId.APPLE, TimingLevel.UNSYNCED, true);
        CatalogState stored = state(apple);
        CatalogSelection pin = new CatalogSelection(TRACK, SelectionMode.MANUAL,
                apple.candidateId, apple.sourceId, "item", apple.canonicalDigest);
        stored = new CatalogState(TRACK, stored.candidates, stored.providers, pin, null, true);
        assertEquals("manual-pin", plan(stored, AUTO, true).reason);
    }

    @Test public void configuredSourceOrderRemainsAnExplicitPreferenceWalk() {
        CatalogPolicy order = new CatalogPolicy(Arrays.asList(SourceId.NETEASE, SourceId.APPLE), true);
        assertEquals(Collections.singletonList(SourceId.NETEASE), plan(state(null), order, true).scope.sources);
    }

    @Test public void priorFailuresSkipToNextDueSourceWithoutErasingTheirRetryHorizon() {
        CatalogState stored = state(null,
                answered(SourceId.SPICY_ORG, ProviderStatus.TRANSIENT_ERROR, NOW, 1),
                answered(SourceId.APPLE, ProviderStatus.NOT_FOUND, NOW, 1));
        Plan next = plan(stored, AUTO, true);
        assertEquals(Collections.singletonList(SourceId.SPOTIFY_NATIVE), next.scope.sources);
        assertEquals(NOW + AcquisitionPlanner.TRANSIENT_BASE_RETRY_MS, next.retryAtMs);
        assertEquals(Collections.singletonList(SourceId.LRCLIB), plan(stored, AUTO, false).scope.sources);
    }

    @Test public void durableMissBecomesDueAfterHorizon() {
        CatalogPolicy orgOnly = new CatalogPolicy(Collections.singletonList(SourceId.SPICY_ORG), false);
        assertFalse(plan(state(null, answered(SourceId.SPICY_ORG, ProviderStatus.NOT_FOUND,
                NOW, 1)), orgOnly, true).fetches());
        assertTrue(plan(state(null, answered(SourceId.SPICY_ORG, ProviderStatus.NOT_FOUND,
                NOW - AcquisitionPlanner.NOT_FOUND_RETRY_MS, 1)), orgOnly, true).fetches());
    }

    @Test public void transientFailuresBackOffToCap() {
        assertEquals(30_000, AcquisitionPlanner.transientBackoffMs(1));
        assertEquals(60_000, AcquisitionPlanner.transientBackoffMs(2));
        assertEquals(120_000, AcquisitionPlanner.transientBackoffMs(3));
        assertEquals(AcquisitionPlanner.TRANSIENT_MAX_RETRY_MS, AcquisitionPlanner.transientBackoffMs(40));
        assertEquals(Long.MAX_VALUE, AcquisitionPlanner.dueAtMs(
                answered(SourceId.APPLE, ProviderStatus.AVAILABLE, NOW, 0), false));
        assertEquals(0, AcquisitionPlanner.dueAtMs(ProviderRecord.notChecked(SourceId.APPLE), false));
        assertEquals(0, AcquisitionPlanner.dueAtMs(answered(SourceId.APPLE,
                ProviderStatus.NEEDS_REFRESH, NOW, 0), false));
    }

    @Test public void ineligibleKaraokeCandidateCanBeRecheckedWithoutKaraokeSubstitution() {
        CatalogCandidate karaoke = CatalogDecisionsTest.cand(SourceId.QQ, "item", "digest",
                TimingLevel.WORD, MatchMethod.KARAOKE_SUBSTITUTION);
        CatalogPolicy qq = new CatalogPolicy(Collections.singletonList(SourceId.QQ), false, false);
        Plan retry = plan(state(karaoke, answered(SourceId.QQ, ProviderStatus.AVAILABLE, NOW, 0)), qq, true);
        assertTrue(retry.fetches());
        assertFalse(retry.scope.karaokeOriginalLyrics);
    }

    @Test public void slowFailuresFinishUnaskedSourcesBeforeDueEarlierRetries() {
        CatalogState failed = state(null,
                answered(SourceId.SPICY_ORG, ProviderStatus.TRANSIENT_ERROR, NOW - 60_000, 1),
                answered(SourceId.APPLE, ProviderStatus.TRANSIENT_ERROR, NOW - 40_000, 1));
        Set<SourceId> attempted = EnumSet.of(SourceId.SPICY_ORG, SourceId.APPLE);
        Plan next = AcquisitionPlanner.plan(failed, AUTO, CatalogDecisions.render(failed, AUTO),
                NOW, true, attempted);
        assertEquals(Collections.singletonList(SourceId.SPOTIFY_NATIVE), next.scope.sources);
        attempted.add(SourceId.SPOTIFY_NATIVE);
        Plan afterNative = AcquisitionPlanner.plan(failed, AUTO, CatalogDecisions.render(failed, AUTO),
                NOW, false, attempted);
        assertEquals(Collections.singletonList(SourceId.LRCLIB), afterNative.scope.sources);
        attempted.add(SourceId.LRCLIB);
        assertEquals(Collections.singletonList(SourceId.AMLL), AcquisitionPlanner.plan(failed,
                AUTO, CatalogDecisions.render(failed, AUTO), NOW, false, attempted).scope.sources);
    }

    @Test public void initialFallbackWalkReachesOptionalDonorsBeforeDuePrimaryRetry() {
        CatalogState failed = state(null,
                answered(SourceId.SPICY_ORG, ProviderStatus.TRANSIENT_ERROR, NOW - 60_000, 1),
                answered(SourceId.APPLE, ProviderStatus.TRANSIENT_ERROR, NOW - 60_000, 1),
                answered(SourceId.SPOTIFY_NATIVE, ProviderStatus.NOT_FOUND, NOW, 1),
                answered(SourceId.LRCLIB, ProviderStatus.NOT_FOUND, NOW, 1),
                answered(SourceId.AMLL, ProviderStatus.NOT_FOUND, NOW, 1));
        Set<SourceId> attempted = EnumSet.of(SourceId.SPICY_ORG, SourceId.APPLE,
                SourceId.SPOTIFY_NATIVE, SourceId.LRCLIB, SourceId.AMLL);
        assertEquals(Collections.singletonList(SourceId.QQ), AcquisitionPlanner.plan(failed,
                AUTO, CatalogDecisions.render(failed, AUTO), NOW, false, attempted).scope.sources);
        attempted.add(SourceId.QQ);
        assertEquals(Collections.singletonList(SourceId.NETEASE), AcquisitionPlanner.plan(failed,
                AUTO, CatalogDecisions.render(failed, AUTO), NOW, false, attempted).scope.sources);
        attempted.add(SourceId.NETEASE);
        assertEquals(Collections.singletonList(SourceId.SPICY_ORG), AcquisitionPlanner.plan(failed,
                AUTO, CatalogDecisions.render(failed, AUTO), NOW, false, attempted).scope.sources);
    }

    @Test public void completedOrgStillSuppressesUnattemptedBackupsDuringDueRefreshRetry() {
        CatalogCandidate org = candidate(SourceId.SPICY_ORG, TimingLevel.LINE, true,
                NOW - 22L * 24 * 60 * 60 * 1000);
        CatalogState stored = state(org, answered(SourceId.SPICY_ORG,
                ProviderStatus.TRANSIENT_ERROR, NOW - 60_000, 1));
        Plan next = AcquisitionPlanner.plan(stored, AUTO, CatalogDecisions.render(stored, AUTO),
                NOW, true, EnumSet.of(SourceId.SPICY_ORG));
        assertEquals(Collections.singletonList(SourceId.SPICY_ORG), next.scope.sources);
        assertEquals("org-refresh", next.reason);
    }

    @Test public void disabledAndExplicitRefreshContractsRemainAvailable() {
        assertEquals("all-sources-disabled", plan(state(null), new CatalogPolicy(null, false), true).reason);
        Plan refresh = AcquisitionPlanner.refreshAll(AUTO);
        assertEquals(AUTO.enabledOrder, refresh.scope.sources);
        Plan ordered = AcquisitionPlanner.refreshAllInOrder(AUTO);
        assertTrue(ordered.scope.sourceOrderMode);
        assertEquals(AUTO.enabledOrder, ordered.scope.sources);
    }

    @Test public void policySnapshotAndScopeKeepUpgradeAndKaraokeIdentity() {
        CatalogPolicy off = new CatalogPolicy(AUTO.enabledOrder, false, true, false);
        CatalogPolicy on = new CatalogPolicy(AUTO.enabledOrder, false, true, true);
        assertNotEquals(off, on);
        assertTrue(AcquisitionPlanner.refreshAll(on).scope.karaokeOriginalLyrics);
        assertTrue(AcquisitionPlanner.refreshAll(on).scope.key().contains("karaoke-original"));
    }
}
