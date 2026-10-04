package com.eza.spicyex.lyrics.catalog;

import com.eza.spicyex.lyrics.catalog.CatalogResolver.Resolution;
import com.eza.spicyex.lyrics.catalog.CatalogSource.SelectionMode;
import com.eza.spicyex.lyrics.catalog.CatalogSource.SourceId;
import com.eza.spicyex.lyrics.providers.SpicyOrgPolicy;

import java.util.List;
import java.util.Set;
import java.util.Collections;

/**
 * Decides whether a track visit asks any provider, and which ones. Pure: the stored catalog
 * state, the policy, and the clock are the only inputs, so a revisit makes the same decision
 * offline, after a restart, and in any callback order.
 *
 * <p>Rules:
 * <ul>
 *   <li>A manual pin stops fallback probes. An enabled org pin still soft-refreshes its source.</li>
 *   <li>A complete anchor stops lower-priority acquisition.</li>
 *   <li>Ask one due source, then re-plan after its terminal outcome. Cached lower-priority
 *       lyrics stay visible while earlier primary sources are checked.</li>
 *   <li>Only the sync-upgrade option opens donor probes; missing verses do not imply aligned timing.</li>
 *   <li>Explicit picker checks bypass this planner entirely: the owner asked for that source.</li>
 * </ul>
 */
public final class AcquisitionPlanner {
    /** A provider that answered "no lyrics" (or returned only a rejected item) is asked again after this. */
    public static final long NOT_FOUND_RETRY_MS = 24L * 60L * 60L * 1000L;
    /** First retry after a transport or server failure; doubles per consecutive failure. */
    public static final long TRANSIENT_BASE_RETRY_MS = 30L * 1000L;
    public static final long TRANSIENT_MAX_RETRY_MS = 6L * 60L * 60L * 1000L;
    /** A static seat re-checks sources that already answered after this long. */
    public static final long STATIC_UPGRADE_RETRY_MS = NOT_FOUND_RETRY_MS;

    private AcquisitionPlanner() {
    }

    public enum Action {
        /** Ask the sources in {@link Plan#scope} now. */
        FETCH,
        /** Ask nothing now; {@link Plan#retryAtMs} says when a source becomes due, or 0 for never. */
        NONE
    }

    public static final class Plan {
        public final Action action;
        public final AcquisitionScope scope;
        /** Wall-clock time the next source becomes due; 0 when nothing ever will this visit. */
        public final long retryAtMs;
        public final String reason;

        Plan(Action action, AcquisitionScope scope, long retryAtMs, String reason) {
            this.action = action;
            this.scope = scope;
            this.retryAtMs = Math.max(0L, retryAtMs);
            this.reason = reason == null ? "" : reason;
        }

        public boolean fetches() {
            return action == Action.FETCH;
        }
    }

    /**
     * @param rendered   what the catalog renders for this state (see {@link CatalogDecisions#render})
     * @param includeLocal false once Spotify native was already tried during this visit
     */
    public static Plan plan(CatalogState state, CatalogPolicy policy, Resolution rendered,
                            long nowMs, boolean includeLocal) {
        return plan(state, policy, rendered, nowMs, includeLocal, Collections.emptySet());
    }

    /** Finish the initial source walk before retrying a source already attempted this visit. */
    public static Plan plan(CatalogState state, CatalogPolicy policy, Resolution rendered,
                            long nowMs, boolean includeLocal, Set<SourceId> attemptedThisVisit) {
        Set<SourceId> attempted = attemptedThisVisit == null
                ? Collections.emptySet() : attemptedThisVisit;
        CatalogPolicy p = policy == null ? new CatalogPolicy(null, false) : policy;
        if (p.enabledOrder.isEmpty()) return none(0L, "all-sources-disabled");
        CatalogCandidate seat = rendered == null ? null : rendered.winner;
        boolean pinned = seat != null && !rendered.temporary
                && state.selection.mode == SelectionMode.MANUAL;
        boolean orgSeat = seat != null && seat.sourceId == SourceId.SPICY_ORG
                && p.enabled(SourceId.SPICY_ORG);
        if (pinned && !orgSeat) return none(0L, "manual-pin");
        List<SourceId> order = autoSources(p);
        int seatIndex = seat == null ? order.size() : order.indexOf(seat.sourceId);
        if (seatIndex < 0) seatIndex = order.size();
        boolean supplement = seat != null && CatalogPolicy.primary(seat.sourceId)
                && p.syncUpgradeEnabled;
        long nextDue = 0L;
        for (int pass = 0; pass < 2; pass++) {
            for (int index = 0; index < order.size(); index++) {
                SourceId source = order.get(index);
                if (attempted.contains(source) != (pass == 1)) continue;
                // A cached fallback can render while an earlier source is checked. It does not
                // suppress that source. A complete anchor never triggers unrelated backup probes.
                boolean earlier = index < seatIndex;
                boolean donor = supplement && CatalogPolicy.syncDonor(source)
                        && source != seat.sourceId;
                boolean orgRefresh = source == SourceId.SPICY_ORG && orgSeat;
                if (pinned && !orgRefresh) continue;
                if (seat != null && !earlier && !donor && !orgRefresh) continue;
                if (source == SourceId.SPOTIFY_NATIVE && !includeLocal) continue;
                ProviderRecord record = state.provider(source);
                long at = record.status == CatalogSource.ProviderStatus.AVAILABLE
                        && hasOnlyPolicyIneligibleCandidates(state, p, source)
                        ? 0L : dueAtMs(record, false);
                if (orgRefresh) {
                    // Soft refresh does not retire the usable 21–30-day anchor. A failed attempt
                    // observes the provider horizon instead of repeatedly probing an overdue row.
                    long refreshAt = seat.fetchedAtMs + SpicyOrgPolicy.REFRESH_AFTER_MS;
                    at = record.status == CatalogSource.ProviderStatus.AVAILABLE ? refreshAt
                            : Math.max(refreshAt, at);
                }
                if (at <= nowMs) {
                    String reason = orgRefresh ? "org-refresh" : seat == null ? "no-seat" : earlier ? "primary-probe"
                            : "sync-upgrade-probe";
                    // Re-plan after this provider's terminal outcome. Never speculate with a
                    // parallel fallback request while a prior route is still pending.
                    return new Plan(Action.FETCH, new AcquisitionScope(
                            java.util.Collections.singletonList(source), true,
                            p.karaokeOriginalLyrics), nextDue, reason);
                }
                if (at != Long.MAX_VALUE && (nextDue == 0L || at < nextDue)) nextDue = at;
            }
        }
        return none(nextDue, pinned ? "manual-pin"
                : seat != null ? "anchor-satisfied" : "no-seat-suppressed");
    }

    /**
     * An owner-requested reload of the current track: every automatic source is asked regardless
     * of stored outcomes. The displayed seat stays until a commit elects a different one.
     */
    public static Plan refreshAll(CatalogPolicy policy) {
        CatalogPolicy p = policy == null ? new CatalogPolicy(null, false) : policy;
        List<SourceId> sources = autoSources(p);
        if (sources.isEmpty()) return none(0L, "all-sources-disabled");
        return new Plan(Action.FETCH, new AcquisitionScope(sources, p.sourceOrderMode,
                p.karaokeOriginalLyrics), 0L,
                "owner-refresh");
    }

    /**
     * An owner-requested re-ask of every enabled source, walked in configured order.
     *
     * <p>This explicit preference walk bypasses stored retry horizons and stops at the first
     * usable response. Automatic acquisition instead asks one due source per plan and re-plans
     * after each outcome. Sync donors are requested only through the supplemental gate.
     */
    public static Plan refreshAllInOrder(CatalogPolicy policy) {
        CatalogPolicy p = policy == null ? new CatalogPolicy(null, false) : policy;
        List<SourceId> sources = autoSources(p);
        if (sources.isEmpty()) return none(0L, "all-sources-disabled");
        return new Plan(Action.FETCH, new AcquisitionScope(sources, true,
                p.karaokeOriginalLyrics), 0L, "owner-refresh-ordered");
    }

    /**
     * When one source's stored outcome makes it worth asking again. {@code Long.MAX_VALUE} means
     * never automatically.
     *
     * @param upgradeProbe true when a static seat already renders; a source that already answered
     *                     is then re-asked only after {@link #STATIC_UPGRADE_RETRY_MS}
     */
    public static long dueAtMs(ProviderRecord record, boolean upgradeProbe) {
        if (record == null) return 0L;
        switch (record.status) {
            case NOT_CHECKED:
            case NEEDS_REFRESH:
                return 0L;
            case TRANSIENT_ERROR:
                return record.lastAttemptMs + transientBackoffMs(record.attemptCount);
            case AVAILABLE:
                // A source that already delivered is only re-asked to look for an upgrade.
                return upgradeProbe ? record.lastAttemptMs + STATIC_UPGRADE_RETRY_MS : Long.MAX_VALUE;
            case NOT_FOUND:
            case REJECTED:
            case DISABLED:
                return record.lastAttemptMs + NOT_FOUND_RETRY_MS;
            default:
                return 0L;
        }
    }

    static long transientBackoffMs(int attempts) {
        long delay = TRANSIENT_BASE_RETRY_MS;
        for (int i = 1; i < Math.max(1, attempts) && delay < TRANSIENT_MAX_RETRY_MS; i++) {
            delay *= 2L;
        }
        return Math.min(delay, TRANSIENT_MAX_RETRY_MS);
    }

    /**
     * Sources an automatic visit may ask, in the configured order. Selection still follows the
     * configured ranking mode after provider outcomes are stored.
     */
    static List<SourceId> autoSources(CatalogPolicy policy) {
        return policy.automaticOrder();
    }

    private static boolean hasOnlyPolicyIneligibleCandidates(CatalogState state,
                                                             CatalogPolicy policy,
                                                             SourceId source) {
        boolean found = false;
        for (CatalogCandidate candidate : state.candidates) {
            if (candidate.sourceId != source) continue;
            found = true;
            if (policy.eligibleForAuto(candidate)) return false;
        }
        return found;
    }

    private static Plan none(long retryAtMs, String reason) {
        return new Plan(Action.NONE, AcquisitionScope.NONE, retryAtMs, reason);
    }
}
