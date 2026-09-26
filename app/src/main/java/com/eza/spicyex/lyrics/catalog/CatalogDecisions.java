package com.eza.spicyex.lyrics.catalog;

import com.eza.spicyex.lyrics.catalog.CatalogLegacyImport.LegacyPick;
import com.eza.spicyex.lyrics.catalog.CatalogResolver.Resolution;
import com.eza.spicyex.lyrics.catalog.CatalogSource.ProviderStatus;
import com.eza.spicyex.lyrics.catalog.CatalogSource.SelectionMode;
import com.eza.spicyex.lyrics.catalog.CatalogSource.SourceId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The catalog's only decision logic. Every event (provider answer, user command, settings change,
 * legacy import) is a pure function from one track's {@link CatalogState} and the
 * {@link CatalogPolicy} to a {@link CatalogChange}: the full write set and the seat that renders
 * afterwards. {@link CatalogStore#transact} reads the state, runs one of these, and applies the
 * result inside a single SQLite transaction, so callback order and restarts cannot disagree.
 *
 * <p>Seat rules: a manual pin is authoritative, even when its provider is disabled. A pin whose
 * candidate is gone renders the Auto winner as a temporary stand-in without clearing the pin.
 * Auto keeps its incumbent unless a challenger is strictly better (see {@link CatalogResolver}).
 */
public final class CatalogDecisions {
    private CatalogDecisions() {
    }

    /** What renders for this state: the pin, a temporary stand-in for a missing pin, or Auto. */
    public static Resolution render(CatalogState state, CatalogPolicy policy) {
        return render(state.candidates, state.selection, policy);
    }

    static Resolution render(List<CatalogCandidate> candidates, CatalogSelection selection,
                             CatalogPolicy policy) {
        CatalogPolicy p = policy == null ? new CatalogPolicy(null, false) : policy;
        if (selection != null && selection.mode == SelectionMode.MANUAL) {
            CatalogCandidate pinned = byId(candidates, selection.candidateId);
            if (pinned != null) {
                return CatalogResolver.resolve(Collections.singletonList(pinned), selection);
            }
            if (selection.candidateId.isEmpty() && selection.sourceId != null) {
                // A source-level pin (imported from a per-track source override) renders that
                // source's best stored candidate.
                List<CatalogCandidate> fromSource = new ArrayList<>();
                for (CatalogCandidate candidate : candidates) {
                    if (candidate.sourceId == selection.sourceId) fromSource.add(candidate);
                }
                Resolution best = CatalogResolver.resolve(fromSource, null);
                if (best.winner != null) {
                    return new Resolution(best.winner, "manual-source:" + best.reason, false);
                }
            }
            Resolution stand = auto(candidates, null, p);
            return new Resolution(stand.winner, "manual-missing-temporary:" + stand.reason, true);
        }
        CatalogCandidate incumbent = selection == null ? null : byId(candidates, selection.candidateId);
        return auto(candidates, incumbent, p);
    }

    /** What Auto elects for this state, ignoring any pin: the picker's Auto row. */
    public static Resolution autoResolution(CatalogState state, CatalogPolicy policy) {
        CatalogPolicy p = policy == null ? new CatalogPolicy(null, false) : policy;
        CatalogCandidate incumbent = state.selection.mode == SelectionMode.AUTO
                ? byId(state.candidates, state.selection.candidateId) : null;
        return auto(state.candidates, incumbent, p);
    }

    /** Re-seats the track against the current policy; Save and every load go through here. */
    public static CatalogChange reconcile(CatalogState state, CatalogPolicy policy,
                                          CatalogTrack track) {
        return change(state, policy, track, state.candidates, null, null, null,
                state.selection, "reconciled");
    }

    /** A provider returned a usable candidate. A rejected provider item is refused, not stored. */
    public static CatalogChange providerSuccess(CatalogState state, CatalogPolicy policy,
                                                CatalogCandidate candidate, CatalogTrack track,
                                                long nowMs) {
        if (candidate == null || candidate.sourceId == null
                || !state.trackId.equals(candidate.trackId)) {
            return CatalogChange.refused(render(state, policy), "invalid-candidate");
        }
        ProviderRecord before = state.provider(candidate.sourceId);
        if (state.isRejected(candidate.sourceId, candidate.providerItemId)) {
            return change(state, policy, track, state.candidates, null,
                    Collections.singletonList(before.failed(ProviderStatus.REJECTED, nowMs)), null,
                    state.selection, "rejected-item");
        }
        List<CatalogCandidate> next = new ArrayList<>();
        List<String> retired = new ArrayList<>();
        CatalogSelection selection = state.selection;
        boolean replaced = false;
        for (CatalogCandidate existing : state.candidates) {
            if (existing.candidateId.equals(candidate.candidateId)) {
                next.add(candidate);
                replaced = true;
            } else if (CatalogAdapters.isPartialNativeDuplicate(existing, candidate)) {
                retired.add(existing.candidateId);
                if (selection.candidateId.equals(existing.candidateId)) {
                    selection = new CatalogSelection(state.trackId, selection.mode,
                            candidate.candidateId, candidate.sourceId, candidate.providerItemId,
                            candidate.canonicalDigest);
                }
            } else {
                next.add(existing);
            }
        }
        if (!replaced) next.add(candidate);
        return change(state, policy, track, next, Collections.singletonList(candidate),
                Collections.singletonList(before.succeeded(nowMs)), retired, selection,
                replaced ? "refreshed" : "stored");
    }

    /** A provider answered without a usable candidate, or could not be reached. */
    public static CatalogChange providerFailure(CatalogState state, CatalogPolicy policy,
                                                SourceId source, ProviderStatus status,
                                                long nowMs) {
        if (source == null) return CatalogChange.refused(render(state, policy), "invalid-source");
        ProviderStatus outcome = status == null || status == ProviderStatus.AVAILABLE
                || status == ProviderStatus.NOT_CHECKED ? ProviderStatus.TRANSIENT_ERROR : status;
        return change(state, policy, null, state.candidates, null,
                Collections.singletonList(state.provider(source).failed(outcome, nowMs)), null,
                state.selection, outcome.name().toLowerCase(java.util.Locale.ROOT));
    }

    /** Pins one stored candidate. The pin survives provider disablement and restarts. */
    public static CatalogChange selectManual(CatalogState state, CatalogPolicy policy,
                                             String candidateId) {
        CatalogCandidate candidate = state.candidate(candidateId);
        if (candidate == null) return CatalogChange.refused(render(state, policy), "missing-candidate");
        CatalogSelection pin = new CatalogSelection(state.trackId, SelectionMode.MANUAL,
                candidate.candidateId, candidate.sourceId, candidate.providerItemId,
                candidate.canonicalDigest);
        return change(state, policy, null, state.candidates, null, null, null, pin, "pinned");
    }

    /** Drops the pin and elects Auto afresh: no incumbent is held over a user reset. */
    public static CatalogChange resetAuto(CatalogState state, CatalogPolicy policy) {
        return change(state, policy, null, state.candidates, null, null, null,
                CatalogSelection.auto(state.trackId), "auto");
    }

    /**
     * Rejects a wrong match: every stored version of that provider item goes, and the item is
     * refused from now on. Other items from the same provider stay eligible.
     */
    public static CatalogChange reject(CatalogState state, CatalogPolicy policy,
                                       String candidateId, long nowMs) {
        CatalogCandidate target = state.candidate(candidateId);
        if (target == null) return CatalogChange.refused(render(state, policy), "missing-candidate");
        List<CatalogCandidate> remaining = new ArrayList<>();
        List<String> deleted = new ArrayList<>();
        for (CatalogCandidate candidate : state.candidates) {
            if (candidate.sourceId == target.sourceId
                    && candidate.providerItemId.equals(target.providerItemId)) {
                deleted.add(candidate.candidateId);
            } else {
                remaining.add(candidate);
            }
        }
        CatalogSelection selection = deleted.contains(state.selection.candidateId)
                ? CatalogSelection.auto(state.trackId) : state.selection;
        return change(state, policy, null, remaining, null, null, deleted, selection,
                "rejected", new CatalogChange.Rejection(target.sourceId, target.providerItemId,
                        nowMs));
    }

    /** Deletes one stored version; a deleted pin returns the track to Auto. */
    public static CatalogChange remove(CatalogState state, CatalogPolicy policy,
                                       String candidateId) {
        CatalogCandidate target = state.candidate(candidateId);
        if (target == null) return CatalogChange.refused(render(state, policy), "missing-candidate");
        List<CatalogCandidate> remaining = new ArrayList<>(state.candidates);
        remaining.remove(target);
        CatalogSelection selection = candidateId.equals(state.selection.candidateId)
                ? CatalogSelection.auto(state.trackId) : state.selection;
        return change(state, policy, null, remaining, null, null,
                Collections.singletonList(candidateId), selection, "removed");
    }

    /**
     * Imports the public release's one-winner record and per-track override, once, into an empty
     * track. Anything already stored for the track wins; the import never overwrites it.
     */
    public static CatalogChange importLegacy(CatalogState state, CatalogPolicy policy,
                                             CatalogCandidate legacy, LegacyPick pick,
                                             CatalogTrack track, long nowMs) {
        if (state.hasHistory()) return CatalogChange.refused(render(state, policy), "already-stored");
        boolean pinned = pick != null && pick.mode == SelectionMode.MANUAL && pick.sourceId != null;
        CatalogCandidate usable = legacy != null && state.trackId.equals(legacy.trackId)
                && !state.isRejected(legacy.sourceId, legacy.providerItemId) ? legacy : null;
        if (usable == null && !pinned) {
            return CatalogChange.refused(render(state, policy), "nothing-to-import");
        }
        List<CatalogCandidate> next = usable == null ? Collections.<CatalogCandidate>emptyList()
                : Collections.singletonList(usable);
        List<ProviderRecord> providers = usable == null ? null : Collections.singletonList(
                new ProviderRecord(usable.sourceId, ProviderStatus.AVAILABLE, nowMs,
                        usable.fetchedAtMs, usable.fetchedAtMs, 0));
        CatalogSelection selection = state.selection;
        if (pinned) {
            boolean link = usable != null && usable.sourceId == pick.sourceId;
            selection = new CatalogSelection(state.trackId, SelectionMode.MANUAL,
                    link ? usable.candidateId : "", pick.sourceId,
                    link ? usable.providerItemId : "", link ? usable.canonicalDigest : "");
        }
        return change(state, policy, track, next, next, providers, null, selection, "imported");
    }

    private static CatalogChange change(CatalogState state, CatalogPolicy policy,
                                        CatalogTrack track, List<CatalogCandidate> candidates,
                                        List<CatalogCandidate> put, List<ProviderRecord> providers,
                                        List<String> deleted, CatalogSelection selection,
                                        String outcome, CatalogChange.Rejection... rejections) {
        CatalogPolicy p = policy == null ? new CatalogPolicy(null, false) : policy;
        CatalogSelection seat = seat(candidates, selection, p, state.trackId);
        Resolution resolution = render(candidates, seat, p);
        List<CatalogChange.Rejection> rejected = new ArrayList<>();
        if (rejections != null) {
            for (CatalogChange.Rejection rejection : rejections) {
                if (rejection != null) rejected.add(rejection);
            }
        }
        return new CatalogChange(track, put, deleted, providers, rejected,
                seat.equals(state.selection) ? null : seat, resolution, outcome, true);
    }

    /** The seat to persist: a pin verbatim, otherwise the Auto winner as the next incumbent. */
    private static CatalogSelection seat(List<CatalogCandidate> candidates,
                                         CatalogSelection selection, CatalogPolicy policy,
                                         String trackId) {
        if (selection != null && selection.mode == SelectionMode.MANUAL) return selection;
        CatalogCandidate incumbent = selection == null ? null : byId(candidates, selection.candidateId);
        CatalogCandidate winner = auto(candidates, incumbent, policy).winner;
        if (winner == null) return CatalogSelection.auto(trackId);
        return new CatalogSelection(trackId, SelectionMode.AUTO, winner.candidateId,
                winner.sourceId, winner.providerItemId, winner.canonicalDigest);
    }

    private static Resolution auto(List<CatalogCandidate> candidates, CatalogCandidate incumbent,
                                   CatalogPolicy policy) {
        List<CatalogCandidate> eligible = new ArrayList<>();
        if (candidates != null) {
            for (CatalogCandidate candidate : candidates) {
                if (policy.eligibleForAuto(candidate)) eligible.add(candidate);
            }
        }
        CatalogCandidate eligibleIncumbent = incumbent != null && policy.eligibleForAuto(incumbent)
                ? incumbent : null;
        return CatalogResolver.resolveConfigured(eligible, CatalogSelection.auto(""), eligibleIncumbent,
                policy.enabledOrder, policy.sourceOrderMode);
    }

    private static CatalogCandidate byId(List<CatalogCandidate> candidates, String candidateId) {
        if (candidates == null || candidateId == null || candidateId.isEmpty()) return null;
        for (CatalogCandidate candidate : candidates) {
            if (candidate != null && candidateId.equals(candidate.candidateId)) return candidate;
        }
        return null;
    }
}
