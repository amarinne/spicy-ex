package com.eza.spicyex.lyrics.catalog;

import static org.junit.Assert.*;
import com.eza.spicyex.lyrics.catalog.CatalogSource.*;
import com.eza.spicyex.lyrics.providers.SpicyOrgPolicy;
import org.junit.Test;
import java.util.*;

/** Org soft refresh keeps the anchor through its grace period and follows provider retry state. */
public class OrgSoftRefreshPlannerTest {
    private static final long NOW = System.currentTimeMillis();
    private static final long DAY = 24L * 60 * 60 * 1000;
    private static final CatalogPolicy POLICY = new CatalogPolicy(Arrays.asList(SourceId.SPICY_ORG,
            SourceId.APPLE, SourceId.SPOTIFY_NATIVE, SourceId.LRCLIB, SourceId.QQ), false);

    private static CatalogCandidate org(long fetchedAt) {
        return new CatalogCandidate("org", "track", SourceId.SPICY_ORG, "item",
                MatchMethod.EXACT_SPOTIFY_ID, .9, 0, TimingLevel.LINE, true, true, false,
                false, false, false, true, "digest", "", "[]", null, 1, 1, fetchedAt);
    }
    private static CatalogState state(CatalogCandidate candidate, ProviderRecord record, boolean pinned) {
        CatalogSelection selection = pinned ? new CatalogSelection("track", SelectionMode.MANUAL,
                candidate.candidateId, candidate.sourceId, "item", "digest") : CatalogSelection.auto("track");
        Map<SourceId, ProviderRecord> records = new EnumMap<>(SourceId.class);
        records.put(SourceId.SPICY_ORG, record);
        return new CatalogState("track", Collections.singletonList(candidate), records, selection, null, true);
    }
    private static ProviderRecord record(ProviderStatus status, long at) {
        return new ProviderRecord(SourceId.SPICY_ORG, status, at, at, at, 1);
    }
    private static AcquisitionPlanner.Plan plan(CatalogState state, CatalogPolicy policy) {
        return AcquisitionPlanner.plan(state, policy, CatalogDecisions.render(state, policy), NOW, true);
    }

    @Test public void youngerOrgSchedulesOriginalFetchPlusTwentyOneDays() {
        long fetched = NOW - 20 * DAY;
        AcquisitionPlanner.Plan plan = plan(state(org(fetched), record(ProviderStatus.AVAILABLE, fetched), false), POLICY);
        assertFalse(plan.fetches());
        assertEquals(fetched + SpicyOrgPolicy.REFRESH_AFTER_MS, plan.retryAtMs);
    }
    @Test public void gracePeriodRequestsOnlyOrgAndKeepsItsCompleteLineAnchor() {
        for (long age : new long[]{21 * DAY, 29 * DAY}) {
            CatalogCandidate candidate = org(NOW - age);
            CatalogState state = state(candidate, record(ProviderStatus.AVAILABLE, candidate.fetchedAtMs), false);
            AcquisitionPlanner.Plan plan = plan(state, POLICY);
            assertEquals(Collections.singletonList(SourceId.SPICY_ORG), plan.scope.sources);
            assertEquals("org-refresh", plan.reason);
            assertSame(candidate, CatalogDecisions.render(state, POLICY).winner);
        }
    }
    @Test public void failedGraceRefreshKeepsOrgAndBlocksBackupsUntilRetryHorizon() {
        CatalogCandidate candidate = org(NOW - 22 * DAY);
        CatalogState state = state(candidate, record(ProviderStatus.TRANSIENT_ERROR, NOW), false);
        AcquisitionPlanner.Plan delayed = plan(state, POLICY);
        assertFalse(delayed.fetches());
        assertEquals(NOW + AcquisitionPlanner.TRANSIENT_BASE_RETRY_MS, delayed.retryAtMs);
        assertSame(candidate, CatalogDecisions.render(state, POLICY).winner);
        AcquisitionPlanner.Plan due = AcquisitionPlanner.plan(state, POLICY,
                CatalogDecisions.render(state, POLICY), delayed.retryAtMs, true);
        assertEquals(Collections.singletonList(SourceId.SPICY_ORG), due.scope.sources);
    }
    @Test public void durableRefreshMissKeepsCacheAndItsExistingRetryHorizon() {
        CatalogState state = state(org(NOW - 22 * DAY), record(ProviderStatus.NOT_FOUND, NOW), false);
        AcquisitionPlanner.Plan plan = plan(state, POLICY);
        assertFalse(plan.fetches());
        assertEquals(NOW + AcquisitionPlanner.NOT_FOUND_RETRY_MS, plan.retryAtMs);
    }
    @Test public void freshSuccessResetsRefreshClockInsteadOfRemainingPerpetuallyDue() {
        CatalogCandidate fresh = org(NOW);
        AcquisitionPlanner.Plan plan = plan(state(fresh, record(ProviderStatus.AVAILABLE, NOW), false), POLICY);
        assertFalse(plan.fetches());
        assertEquals(NOW + SpicyOrgPolicy.REFRESH_AFTER_MS, plan.retryAtMs);
    }
    @Test public void enabledOrgManualPinRefreshesButDisabledOrgManualPinDoesNotProbe() {
        CatalogCandidate candidate = org(NOW - 22 * DAY);
        CatalogState pinned = state(candidate, record(ProviderStatus.AVAILABLE, candidate.fetchedAtMs), true);
        assertEquals(Collections.singletonList(SourceId.SPICY_ORG), plan(pinned, POLICY).scope.sources);
        CatalogPolicy disabled = new CatalogPolicy(Collections.singletonList(SourceId.APPLE), false);
        assertEquals("manual-pin", plan(pinned, disabled).reason);
        assertFalse(plan(pinned, disabled).fetches());
    }
    @Test public void thirtyDayExpiredOrgCannotRemainAnchorDuringRefreshFailure() {
        CatalogCandidate expired = org(NOW - SpicyOrgPolicy.RETENTION_MS);
        CatalogState state = state(expired, record(ProviderStatus.TRANSIENT_ERROR, NOW), false);
        assertNull(CatalogDecisions.render(state, POLICY).winner);
        assertEquals(Collections.singletonList(SourceId.APPLE), plan(state, POLICY).scope.sources);
    }
    @Test public void directProviderCacheHasNoOrgSoftRefreshClock() {
        CatalogCandidate apple = new CatalogCandidate("apple", "track", SourceId.APPLE, "item",
                MatchMethod.EXACT_SPOTIFY_ID, .9, 0, TimingLevel.LINE, true, true, false,
                false, false, false, true, "digest", "", "[]", null, 1, 1, NOW - 22 * DAY);
        CatalogPolicy direct = new CatalogPolicy(Arrays.asList(SourceId.APPLE, SourceId.SPOTIFY_NATIVE), false);
        CatalogState state = new CatalogState("track", Collections.singletonList(apple), null,
                CatalogSelection.auto("track"), null, true);
        assertFalse(plan(state, direct).fetches());
        assertEquals(0, plan(state, direct).retryAtMs);
    }
}
