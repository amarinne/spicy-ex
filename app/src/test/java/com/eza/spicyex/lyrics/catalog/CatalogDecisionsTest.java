package com.eza.spicyex.lyrics.catalog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.eza.spicyex.lyrics.catalog.CatalogResolver.Resolution;
import com.eza.spicyex.lyrics.catalog.CatalogSource.MatchMethod;
import com.eza.spicyex.lyrics.catalog.CatalogSource.ProviderStatus;
import com.eza.spicyex.lyrics.catalog.CatalogSource.SelectionMode;
import com.eza.spicyex.lyrics.catalog.CatalogSource.SourceId;
import com.eza.spicyex.lyrics.catalog.CatalogSource.TimingLevel;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Decision-trace corpus for the catalog reducer. Each case states the event, the stored state,
 * and the seat that must render afterwards. Expected outcomes come from the product contract,
 * not from the callback order the old repository/session paths happened to produce.
 */
public class CatalogDecisionsTest {
    private static final String TRACK = "track1";
    private static final long NOW = 1_000_000L;
    private static final CatalogPolicy AUTO = new CatalogPolicy(Arrays.asList(SourceId.APPLE,
            SourceId.SPOTIFY_NATIVE, SourceId.AMLL, SourceId.LRCLIB), false);

    static CatalogCandidate cand(SourceId source, String item, String digest, TimingLevel timing,
                                 MatchMethod method) {
        return new CatalogCandidate(CatalogCandidate.stableId(TRACK, source, item, digest), TRACK,
                source, item, method, 0.8, 0L, timing, true, true, false, false, false, false,
                false, digest, "{}", "[]", new byte[0], 2, 1, NOW);
    }

    static CatalogCandidate apple(String digest) {
        return cand(SourceId.APPLE, TRACK, digest, TimingLevel.SYLLABLE, MatchMethod.EXACT_SPOTIFY_ID);
    }

    static CatalogCandidate lrclib(String item, String digest) {
        return cand(SourceId.LRCLIB, item, digest, TimingLevel.LINE, MatchMethod.STRONG_SEARCH);
    }

    static CatalogCandidate nativeStatic(String digest) {
        return cand(SourceId.SPOTIFY_NATIVE, TRACK, digest, TimingLevel.UNSYNCED,
                MatchMethod.EXACT_SPOTIFY_ID);
    }

    /** Applies a change to a state exactly as {@link CatalogStore#apply} would. */
    static CatalogState apply(CatalogState state, CatalogChange change) {
        if (!change.accepted) return state;
        List<CatalogCandidate> candidates = new ArrayList<>();
        for (CatalogCandidate candidate : state.candidates) {
            if (!change.deleteCandidateIds.contains(candidate.candidateId)) candidates.add(candidate);
        }
        for (CatalogCandidate put : change.putCandidates) {
            boolean replaced = false;
            for (int i = 0; i < candidates.size(); i++) {
                if (candidates.get(i).candidateId.equals(put.candidateId)) {
                    candidates.set(i, put);
                    replaced = true;
                }
            }
            if (!replaced) candidates.add(put);
        }
        Map<SourceId, ProviderRecord> providers = new EnumMap<>(SourceId.class);
        providers.putAll(state.providers);
        for (ProviderRecord record : change.putProviders) providers.put(record.source, record);
        Set<String> rejections = new HashSet<>(state.rejections);
        for (CatalogChange.Rejection rejection : change.putRejections) {
            rejections.add(CatalogState.rejectionKey(rejection.source, rejection.providerItemId));
        }
        CatalogSelection selection = change.selection == null ? state.selection : change.selection;
        return new CatalogState(state.trackId, candidates, providers, selection, rejections,
                state.known || change.track != null);
    }

    static CatalogState success(CatalogState state, CatalogPolicy policy, CatalogCandidate candidate) {
        return apply(state, CatalogDecisions.providerSuccess(state, policy, candidate, null, NOW));
    }

    static String seat(CatalogState state, CatalogPolicy policy) {
        Resolution resolution = CatalogDecisions.render(state, policy);
        return resolution.winner == null ? "" : resolution.winner.candidateId;
    }

    // --- catalog miss and hit ---------------------------------------------------------------

    @Test
    public void aFirstProviderSuccessSeatsTheCandidateAndRecordsTheOutcome() {
        CatalogState empty = CatalogState.empty(TRACK);
        CatalogCandidate lrc = lrclib("lrc-1", "d1");
        CatalogChange change = CatalogDecisions.providerSuccess(empty, AUTO, lrc, null, NOW);

        assertTrue(change.accepted);
        assertEquals("stored", change.outcome);
        assertEquals(lrc.candidateId, change.selection.candidateId);
        assertEquals(SelectionMode.AUTO, change.selection.mode);
        assertEquals(ProviderStatus.AVAILABLE, change.putProviders.get(0).status);
        assertEquals(NOW, change.putProviders.get(0).lastSuccessMs);
        assertEquals(lrc.candidateId, change.resolution.winner.candidateId);
    }

    @Test
    public void aStoredSeatRendersOfflineWithoutAnyWrite() {
        CatalogState stored = success(CatalogState.empty(TRACK), AUTO, apple("d1"));
        CatalogChange revisit = CatalogDecisions.reconcile(stored, AUTO, null);

        assertNull(revisit.selection);
        assertTrue(revisit.writesNothing());
        assertEquals(apple("d1").candidateId, revisit.resolution.winner.candidateId);
    }

    // --- duplicate and out-of-order callbacks -----------------------------------------------

    @Test
    public void aDuplicateCallbackRefreshesTheSameRowAndKeepsTheSeat() {
        CatalogState once = success(CatalogState.empty(TRACK), AUTO, apple("d1"));
        CatalogChange again = CatalogDecisions.providerSuccess(once, AUTO, apple("d1"), null, NOW);

        assertEquals("refreshed", again.outcome);
        assertNull(again.selection);
        assertEquals(1, apply(once, again).candidates.size());
    }

    @Test
    public void callbackOrderElectsTheSameStrictlyBetterSeat() {
        CatalogState nativeFirst = success(success(CatalogState.empty(TRACK), AUTO,
                nativeStatic("n1")), AUTO, apple("a1"));
        CatalogState appleFirst = success(success(CatalogState.empty(TRACK), AUTO,
                apple("a1")), AUTO, nativeStatic("n1"));

        assertEquals(apple("a1").candidateId, seat(nativeFirst, AUTO));
        assertEquals(apple("a1").candidateId, seat(appleFirst, AUTO));
    }

    @Test
    public void anEqualRankedLateArrivalNeverFlipsTheSeatedIncumbent() {
        CatalogCandidate first = lrclib("lrc-b", "d-b");
        CatalogCandidate second = lrclib("lrc-a", "d-a");
        CatalogState seated = success(CatalogState.empty(TRACK), AUTO, first);
        CatalogState after = success(seated, AUTO, second);

        assertEquals(first.candidateId, seat(after, AUTO));
        assertEquals(2, after.candidates.size());
    }

    // --- synced and unsynced bases ----------------------------------------------------------

    @Test
    public void aSyncedSeatUpgradesWhenAStrictlyBetterCandidateArrives() {
        CatalogState lineSeat = success(CatalogState.empty(TRACK), AUTO, lrclib("lrc-1", "d1"));
        CatalogState upgraded = success(lineSeat, AUTO, apple("a1"));

        assertEquals(apple("a1").candidateId, seat(upgraded, AUTO));
        assertEquals(2, upgraded.candidates.size());
    }

    @Test
    public void aLowerQualityArrivalIsStoredButNeverDisplacesTheSeat() {
        CatalogState syllable = success(CatalogState.empty(TRACK), AUTO, apple("a1"));
        CatalogState after = success(syllable, AUTO, nativeStatic("n1"));

        assertEquals(apple("a1").candidateId, seat(after, AUTO));
        assertEquals(2, after.candidates.size());
    }

    // --- Auto versus Source order and disabled providers -----------------------------------

    @Test
    public void sourceOrderSeatsTheFirstEnabledSourceEvenOverBetterTiming() {
        CatalogPolicy order = new CatalogPolicy(Arrays.asList(SourceId.LRCLIB, SourceId.APPLE), true);
        CatalogState state = success(success(CatalogState.empty(TRACK), order, apple("a1")), order,
                lrclib("lrc-1", "d1"));

        assertEquals(lrclib("lrc-1", "d1").candidateId, seat(state, order));
        assertEquals(apple("a1").candidateId, seat(state, AUTO));
    }

    @Test
    public void disablingAProviderRemovesItFromAutoButKeepsItsData() {
        CatalogState state = success(success(CatalogState.empty(TRACK), AUTO, apple("a1")), AUTO,
                lrclib("lrc-1", "d1"));
        CatalogPolicy noApple = new CatalogPolicy(Arrays.asList(SourceId.SPOTIFY_NATIVE,
                SourceId.LRCLIB), false);
        CatalogChange reconciled = CatalogDecisions.reconcile(state, noApple, null);

        assertEquals(lrclib("lrc-1", "d1").candidateId, reconciled.selection.candidateId);
        assertTrue(reconciled.deleteCandidateIds.isEmpty());
        assertEquals(2, apply(state, reconciled).candidates.size());
    }

    @Test
    public void karaokeSubstitutionIsOptInForAutoButRemainsStored() {
        CatalogPolicy off = new CatalogPolicy(Collections.singletonList(SourceId.QQ), false, false);
        CatalogPolicy on = new CatalogPolicy(Collections.singletonList(SourceId.QQ), false, true);
        assertFalse(off.equals(on));
        CatalogCandidate karaoke = cand(SourceId.QQ, "song", "k1", TimingLevel.WORD,
                MatchMethod.KARAOKE_SUBSTITUTION);
        CatalogState stored = success(CatalogState.empty(TRACK), on, karaoke);

        CatalogChange disabled = CatalogDecisions.reconcile(stored, off, null);
        CatalogState withoutSeat = apply(stored, disabled);
        assertNull(disabled.resolution.winner);
        assertEquals("", withoutSeat.selection.candidateId);
        assertEquals(1, withoutSeat.candidates.size());

        CatalogChange enabled = CatalogDecisions.reconcile(withoutSeat, on, null);
        assertEquals(karaoke.candidateId, enabled.resolution.winner.candidateId);
        assertEquals(karaoke.candidateId, enabled.selection.candidateId);
    }

    // --- manual pins ------------------------------------------------------------------------

    @Test
    public void aManualPinSurvivesBetterArrivalsAndItsProviderBeingDisabled() {
        CatalogCandidate lrc = lrclib("lrc-1", "d1");
        CatalogState stored = success(CatalogState.empty(TRACK), AUTO, lrc);
        CatalogState pinned = apply(stored, CatalogDecisions.selectManual(stored, AUTO,
                lrc.candidateId));
        CatalogState better = success(pinned, AUTO, apple("a1"));
        CatalogPolicy lrclibOff = new CatalogPolicy(Collections.singletonList(SourceId.APPLE), false);

        assertEquals(lrc.candidateId, seat(better, AUTO));
        assertEquals(lrc.candidateId, seat(better, lrclibOff));
        assertNull(CatalogDecisions.reconcile(better, lrclibOff, null).selection);
    }

    @Test
    public void manualKaraokeSubstitutionRemainsReadableWhenAutomaticUseIsOff() {
        CatalogPolicy on = new CatalogPolicy(Collections.singletonList(SourceId.QQ), false, true);
        CatalogPolicy off = new CatalogPolicy(Collections.singletonList(SourceId.QQ), false, false);
        CatalogCandidate karaoke = cand(SourceId.QQ, "song", "k1", TimingLevel.WORD,
                MatchMethod.KARAOKE_SUBSTITUTION);
        CatalogState stored = success(CatalogState.empty(TRACK), on, karaoke);
        CatalogState pinned = apply(stored, CatalogDecisions.selectManual(stored, on,
                karaoke.candidateId));

        assertEquals(karaoke.candidateId, seat(pinned, off));
        assertNull(CatalogDecisions.reconcile(pinned, off, null).selection);
    }

    @Test
    public void aMissingPinRendersAutoAsATemporaryStandInWithoutClearingThePin() {
        CatalogState stored = success(CatalogState.empty(TRACK), AUTO, apple("a1"));
        CatalogState pinnedGone = new CatalogState(TRACK, stored.candidates, stored.providers,
                new CatalogSelection(TRACK, SelectionMode.MANUAL, "gone", SourceId.LRCLIB,
                        "lrc-x", "dx"), null, true);
        Resolution resolution = CatalogDecisions.render(pinnedGone, AUTO);
        CatalogChange reconciled = CatalogDecisions.reconcile(pinnedGone, AUTO, null);

        assertTrue(resolution.temporary);
        assertEquals(apple("a1").candidateId, resolution.winner.candidateId);
        assertNull(reconciled.selection);
    }

    @Test
    public void aSourceLevelPinRendersThatSourcesBestCandidate() {
        CatalogState stored = success(success(CatalogState.empty(TRACK), AUTO, apple("a1")), AUTO,
                lrclib("lrc-1", "d1"));
        CatalogState sourcePin = new CatalogState(TRACK, stored.candidates, stored.providers,
                new CatalogSelection(TRACK, SelectionMode.MANUAL, "", SourceId.LRCLIB, "", ""),
                null, true);
        Resolution resolution = CatalogDecisions.render(sourcePin, AUTO);

        assertFalse(resolution.temporary);
        assertEquals(lrclib("lrc-1", "d1").candidateId, resolution.winner.candidateId);
    }

    @Test
    public void resetToAutoDropsThePinAndElectsAfresh() {
        CatalogCandidate lrc = lrclib("lrc-1", "d1");
        CatalogState stored = success(success(CatalogState.empty(TRACK), AUTO, lrc), AUTO,
                apple("a1"));
        CatalogState pinned = apply(stored, CatalogDecisions.selectManual(stored, AUTO,
                lrc.candidateId));
        CatalogState reset = apply(pinned, CatalogDecisions.resetAuto(pinned, AUTO));

        assertEquals(SelectionMode.AUTO, reset.selection.mode);
        assertEquals(apple("a1").candidateId, reset.selection.candidateId);
    }

    @Test
    public void pinningAnUnknownCandidateIsRefusedAndWritesNothing() {
        CatalogChange change = CatalogDecisions.selectManual(CatalogState.empty(TRACK), AUTO, "nope");
        assertFalse(change.accepted);
        assertEquals("missing-candidate", change.outcome);
    }

    // --- rejection and removal --------------------------------------------------------------

    @Test
    public void rejectingAMatchRemovesEveryVersionOfThatItemAndRefusesItLater() {
        CatalogState state = success(success(success(CatalogState.empty(TRACK), AUTO,
                lrclib("wrong", "v1")), AUTO, lrclib("wrong", "v2")), AUTO, lrclib("right", "r1"));
        CatalogChange reject = CatalogDecisions.reject(state, AUTO,
                lrclib("wrong", "v1").candidateId, NOW);
        CatalogState rejected = apply(state, reject);

        assertEquals(2, reject.deleteCandidateIds.size());
        assertEquals(1, rejected.candidates.size());
        assertTrue(rejected.isRejected(SourceId.LRCLIB, "wrong"));
        assertFalse(rejected.isRejected(SourceId.LRCLIB, "right"));
        assertTrue(reject.putProviders.isEmpty());

        CatalogChange again = CatalogDecisions.providerSuccess(rejected, AUTO,
                lrclib("wrong", "v3"), null, NOW);
        assertEquals("rejected-item", again.outcome);
        assertTrue(again.putCandidates.isEmpty());
        assertEquals(ProviderStatus.REJECTED, again.putProviders.get(0).status);
    }

    @Test
    public void rejectingThePinnedCandidateReturnsTheTrackToAuto() {
        CatalogCandidate wrong = lrclib("wrong", "v1");
        CatalogState stored = success(success(CatalogState.empty(TRACK), AUTO, wrong), AUTO,
                nativeStatic("n1"));
        CatalogState pinned = apply(stored, CatalogDecisions.selectManual(stored, AUTO,
                wrong.candidateId));
        CatalogState after = apply(pinned, CatalogDecisions.reject(pinned, AUTO,
                wrong.candidateId, NOW));

        assertEquals(SelectionMode.AUTO, after.selection.mode);
        assertEquals(nativeStatic("n1").candidateId, after.selection.candidateId);
    }

    @Test
    public void removingTheLastCandidateLeavesAnEmptyAutoSeat() {
        CatalogCandidate only = apple("a1");
        CatalogState stored = success(CatalogState.empty(TRACK), AUTO, only);
        CatalogChange removed = CatalogDecisions.remove(stored, AUTO, only.candidateId);

        assertEquals(Collections.singletonList(only.candidateId), removed.deleteCandidateIds);
        assertNull(removed.resolution.winner);
        assertEquals("", removed.selection.candidateId);
    }

    // --- failures ---------------------------------------------------------------------------

    @Test
    public void transientFailuresCountUpAndDurableAnswersRestartTheCount() {
        CatalogState state = success(CatalogState.empty(TRACK), AUTO, apple("a1"));
        CatalogState one = apply(state, CatalogDecisions.providerFailure(state, AUTO,
                SourceId.LRCLIB, ProviderStatus.TRANSIENT_ERROR, NOW));
        CatalogState two = apply(one, CatalogDecisions.providerFailure(one, AUTO,
                SourceId.LRCLIB, ProviderStatus.TRANSIENT_ERROR, NOW + 1));
        CatalogState durable = apply(two, CatalogDecisions.providerFailure(two, AUTO,
                SourceId.LRCLIB, ProviderStatus.NOT_FOUND, NOW + 2));

        assertEquals(1, one.provider(SourceId.LRCLIB).attemptCount);
        assertEquals(2, two.provider(SourceId.LRCLIB).attemptCount);
        assertEquals(1, durable.provider(SourceId.LRCLIB).attemptCount);
        assertEquals(NOW + 2, durable.provider(SourceId.LRCLIB).lastAttemptMs);
        assertEquals(apple("a1").candidateId, seat(durable, AUTO));
    }

    @Test
    public void aFailureNeverTouchesStoredCandidatesOrTheSeat() {
        CatalogState state = success(CatalogState.empty(TRACK), AUTO, apple("a1"));
        CatalogChange failure = CatalogDecisions.providerFailure(state, AUTO, SourceId.APPLE,
                ProviderStatus.NOT_FOUND, NOW);

        assertTrue(failure.putCandidates.isEmpty());
        assertTrue(failure.deleteCandidateIds.isEmpty());
        assertNull(failure.selection);
        assertEquals(1, apply(state, failure).candidates.size());
    }

    // --- public release import --------------------------------------------------------------

    @Test
    public void anUntouchedTrackImportsThePublicReleaseWinnerOnce() {
        CatalogCandidate legacy = cand(SourceId.APPLE, "legacy-winner", "L1", TimingLevel.LINE,
                MatchMethod.STRONG_SEARCH);
        CatalogLegacyImport.LegacyPick auto = CatalogLegacyImport.pickForLegacyOverride(null);
        CatalogTrack row = new CatalogTrack(TRACK, "spotify:track:" + TRACK, "t", "a", "b", 1L, NOW);
        CatalogChange imported = CatalogDecisions.importLegacy(CatalogState.empty(TRACK), AUTO,
                legacy, auto, row, NOW);
        CatalogState after = apply(CatalogState.empty(TRACK), imported);

        assertEquals("imported", imported.outcome);
        assertEquals(legacy.candidateId, after.selection.candidateId);
        assertFalse(CatalogDecisions.importLegacy(after, AUTO, legacy, auto, row, NOW).accepted);
    }

    @Test
    public void aTrackWithHistoryNeverReimportsEvenWhenItsCandidatesWereDeleted() {
        CatalogState emptiedButKnown = new CatalogState(TRACK, null, null, null, null, true);
        CatalogCandidate legacy = cand(SourceId.APPLE, "legacy-winner", "L1", TimingLevel.LINE,
                MatchMethod.STRONG_SEARCH);

        assertFalse(CatalogDecisions.importLegacy(emptiedButKnown, AUTO, legacy,
                CatalogLegacyImport.pickForLegacyOverride(null), null, NOW).accepted);
    }

    @Test
    public void aLegacyOverrideBecomesAPinLinkedToTheMatchingWinner() {
        CatalogCandidate legacy = cand(SourceId.LRCLIB, "legacy-winner", "L1", TimingLevel.LINE,
                MatchMethod.STRONG_SEARCH);
        CatalogState linked = apply(CatalogState.empty(TRACK), CatalogDecisions.importLegacy(
                CatalogState.empty(TRACK), AUTO, legacy,
                CatalogLegacyImport.pickForLegacyOverride("lrclib"), null, NOW));
        CatalogState sourceOnly = apply(CatalogState.empty(TRACK), CatalogDecisions.importLegacy(
                CatalogState.empty(TRACK), AUTO, null,
                CatalogLegacyImport.pickForLegacyOverride("qq"), null, NOW));

        assertEquals(SelectionMode.MANUAL, linked.selection.mode);
        assertEquals(legacy.candidateId, linked.selection.candidateId);
        assertEquals(SelectionMode.MANUAL, sourceOnly.selection.mode);
        assertEquals("", sourceOnly.selection.candidateId);
        assertEquals(SourceId.QQ, sourceOnly.selection.sourceId);
    }

    @Test
    public void aCandidateForAnotherTrackIsRefused() {
        CatalogCandidate other = new CatalogCandidate("x", "other", SourceId.APPLE, "i",
                MatchMethod.EXACT_SPOTIFY_ID, 1.0, 0L, TimingLevel.LINE, true, true, false, false,
                false, false, false, "d", "{}", "[]", new byte[0], 1, 1, NOW);
        CatalogChange change = CatalogDecisions.providerSuccess(CatalogState.empty(TRACK), AUTO,
                other, null, NOW);
        assertFalse(change.accepted);
        assertNotNull(change.resolution);
    }
}
