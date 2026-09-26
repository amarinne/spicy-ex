package com.eza.spicyex.lyrics.catalog;

import com.eza.spicyex.lyrics.catalog.CatalogResolver.Resolution;
import com.eza.spicyex.lyrics.catalog.CatalogSource.SelectionMode;
import com.eza.spicyex.lyrics.catalog.CatalogSource.SourceId;
import com.eza.spicyex.lyrics.catalog.CatalogSource.TimingLevel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Decides whether a track visit asks any provider, and which ones. Pure: the stored catalog
 * state, the policy, and the clock are the only inputs, so a revisit makes the same decision
 * offline, after a restart, and in any callback order.
 *
 * <p>Rules:
 * <ul>
 *   <li>A manual pin, or a synced complete seat, is final: revisiting it costs no request.</li>
 *   <li>With no seat, or a static/incomplete one, only sources whose stored outcome is due are
 *       asked. The retry horizon per outcome lives in {@link #dueAtMs} and nowhere else.</li>
 *   <li>Spotify native is local and free, so it is eligible once per visit regardless of state.</li>
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
        CatalogPolicy p = policy == null ? new CatalogPolicy(null, false) : policy;
        if (p.enabledOrder.isEmpty()) return none(0L, "all-sources-disabled");
        CatalogCandidate seat = rendered == null ? null : rendered.winner;
        boolean pinned = seat != null && !rendered.temporary
                && state.selection.mode == SelectionMode.MANUAL;
        if (pinned) return none(0L, "manual-pin");
        if (seat != null && seat.complete && seat.timingLevel.isFinalQuality()) {
            return none(0L, "final-seat");
        }
        boolean upgradeProbe = seat != null;
        List<SourceId> due = new ArrayList<>();
        long nextDue = 0L;
        for (SourceId source : autoSources(p)) {
            if (source == SourceId.SPOTIFY_NATIVE) {
                if (includeLocal) due.add(source);
                continue;
            }
            ProviderRecord record = state.provider(source);
            long at = record.status == CatalogSource.ProviderStatus.AVAILABLE
                    && hasOnlyPolicyIneligibleCandidates(state, p, source)
                    ? 0L : dueAtMs(record, upgradeProbe);
            if (at <= nowMs) {
                due.add(source);
            } else if (at != Long.MAX_VALUE && (nextDue == 0L || at < nextDue)) {
                nextDue = at;
            }
        }
        boolean anyNetwork = false;
        for (SourceId source : due) {
            if (source != SourceId.SPOTIFY_NATIVE) anyNetwork = true;
        }
        if (due.isEmpty() || (upgradeProbe && !anyNetwork)) {
            return none(nextDue, upgradeProbe ? "upgrade-suppressed" : "no-seat-suppressed");
        }
        return new Plan(Action.FETCH, new AcquisitionScope(due, p.sourceOrderMode,
                p.karaokeOriginalLyrics), nextDue,
                upgradeProbe ? "upgrade-probe" : "no-seat");
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
     * An owner-requested re-ask of the whole quality chain, walked strictly in the built-in order
     * (Apple, Spotify native, AMLL, LRCLIB).
     *
     * <p>This is the escape hatch for a track whose stored sources were never asked. It forces the
     * sequential path rather than the automatic visit's racing provider chain, so the outcome
     * reflects the configured preference instead of whichever adapter happened to answer first.
     *
     * <p>It is a preference walk, not a quality escalation: {@code attemptOrderedSource} recurses on
     * failure and stops at the first source that returns any lyrics, whatever its timing. Escalating
     * a line-timed seat to word or syllable timing is the automatic visit's job, via the WORD
     * threshold in {@link #plan}. Do not describe this as an upgrade path.
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
     * Sources an automatic visit may ask, in ask order. Auto uses the built-in quality chain
     * (Apple, native, AMLL, LRCLIB); QQ and NetEase are explicit-check sources there. Source order
     * asks every enabled source in the owner's order.
     */
    static List<SourceId> autoSources(CatalogPolicy policy) {
        if (policy.sourceOrderMode) return policy.enabledOrder;
        List<SourceId> out = new ArrayList<>();
        for (SourceId source : new SourceId[]{SourceId.APPLE, SourceId.SPOTIFY_NATIVE,
                SourceId.AMLL, SourceId.LRCLIB}) {
            if (policy.enabled(source)) out.add(source);
        }
        return Collections.unmodifiableList(out);
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
