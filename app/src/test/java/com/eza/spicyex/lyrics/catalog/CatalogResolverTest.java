package com.eza.spicyex.lyrics.catalog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.eza.spicyex.lyrics.catalog.CatalogResolver.Resolution;
import com.eza.spicyex.lyrics.catalog.CatalogSource.MatchMethod;
import com.eza.spicyex.lyrics.catalog.CatalogSource.SelectionMode;
import com.eza.spicyex.lyrics.catalog.CatalogSource.SourceId;
import com.eza.spicyex.lyrics.catalog.CatalogSource.TimingLevel;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 20-song resolver corpus: wrong versions, incomplete provider data, static lyrics, obscure
 * tracks, ties, downgrade refusal, upgrades, and manual pins. Reasons carry IDs and ranks only.
 */
public class CatalogResolverTest {
    private static int nextId;

    private static CatalogCandidate cand(SourceId source, MatchMethod method, TimingLevel timing,
                                         boolean complete) {
        return cand(source, method, timing, complete, 0.9, 0L, true, false, "d" + (nextId++));
    }

    private static CatalogCandidate cand(SourceId source, MatchMethod method, TimingLevel timing,
                                         boolean complete, double confidence, long deltaMs,
                                         boolean healthy, boolean caps, String digest) {
        String id = source.id + "|item|" + digest + "#" + (nextId++);
        return new CatalogCandidate(id, "track", source, "item", method, confidence, deltaMs,
                timing, complete, healthy, caps, caps, caps, caps, caps, digest, "{}", "[]",
                new byte[0], 1, 1, 0L);
    }

    @Test
    public void wordTimingBeatsLineAcrossSources() {
        CatalogCandidate exact = cand(SourceId.APPLE, MatchMethod.EXACT_SPOTIFY_ID,
                TimingLevel.LINE, true);
        CatalogCandidate word = cand(SourceId.LRCLIB, MatchMethod.STRONG_SEARCH,
                TimingLevel.WORD, true);
        assertEquals(word.candidateId,
                CatalogResolver.resolve(Arrays.asList(word, exact), null).winner.candidateId);
    }

    @Test
    public void weakRemasterAndLiveVersionsAreRejected() {
        CatalogCandidate weak = cand(SourceId.APPLE, MatchMethod.WEAK, TimingLevel.SYLLABLE, true);
        assertNull(CatalogResolver.resolve(Collections.singletonList(weak), null).winner);
    }

    @Test
    public void completenessDecidesWithinTimingClass() {
        CatalogCandidate incompleteExact = cand(SourceId.APPLE, MatchMethod.EXACT_SPOTIFY_ID,
                TimingLevel.LINE, false);
        CatalogCandidate completeSearch = cand(SourceId.LRCLIB, MatchMethod.STRONG_SEARCH,
                TimingLevel.LINE, true);
        Resolution resolution = CatalogResolver.resolve(
                Arrays.asList(completeSearch, incompleteExact), null);
        assertEquals(completeSearch.candidateId, resolution.winner.candidateId);
        assertTrue(resolution.reason.contains("complete"));
    }

    @Test
    public void staticLyricsWinWhenNothingIsTimed() {
        CatalogCandidate unsynced = cand(SourceId.SPOTIFY_NATIVE, MatchMethod.EXACT_SPOTIFY_ID,
                TimingLevel.UNSYNCED, true);
        assertEquals(unsynced.candidateId,
                CatalogResolver.resolve(Collections.singletonList(unsynced), null)
                        .winner.candidateId);
    }

    @Test
    public void obscureTrackWithOnlyLrclibSearchStillResolves() {
        CatalogCandidate only = cand(SourceId.LRCLIB, MatchMethod.STRONG_SEARCH,
                TimingLevel.LINE, true);
        Resolution resolution = CatalogResolver.resolve(Collections.singletonList(only), null);
        assertEquals(only.candidateId, resolution.winner.candidateId);
        assertTrue(resolution.reason.startsWith("auto:lrclib"));
    }

    @Test
    public void tiesAreDeterministicRegardlessOfCallbackOrder() {
        CatalogCandidate a = cand(SourceId.AMLL, MatchMethod.STRONG_SEARCH, TimingLevel.LINE,
                true, 0.9, 0L, true, false, "same");
        CatalogCandidate b = cand(SourceId.AMLL, MatchMethod.STRONG_SEARCH, TimingLevel.LINE,
                true, 0.9, 0L, true, false, "same");
        String first = CatalogResolver.resolve(Arrays.asList(a, b), null).winner.candidateId;
        String second = CatalogResolver.resolve(Arrays.asList(b, a), null).winner.candidateId;
        assertEquals(first, second);
    }

    @Test
    public void incumbentHoldsAgainstEqualRankChallenger() {
        CatalogCandidate incumbent = cand(SourceId.APPLE, MatchMethod.EXACT_SPOTIFY_ID,
                TimingLevel.LINE, true, 0.9, 0L, true, false, "zzz");
        CatalogCandidate challenger = cand(SourceId.APPLE, MatchMethod.EXACT_SPOTIFY_ID,
                TimingLevel.LINE, true, 0.9, 0L, true, false, "aaa");
        Resolution resolution = CatalogResolver.resolveWithIncumbent(
                Arrays.asList(challenger, incumbent), null, incumbent);
        assertEquals(incumbent.candidateId, resolution.winner.candidateId);
        assertTrue(resolution.reason.contains("hold"));
    }

    @Test
    public void wordChallengerUpgradesLineIncumbent() {
        CatalogCandidate incumbent = cand(SourceId.APPLE, MatchMethod.EXACT_SPOTIFY_ID,
                TimingLevel.LINE, true, 0.9, 0L, true, false, "aaa");
        CatalogCandidate challenger = cand(SourceId.SPOTIFY_NATIVE, MatchMethod.STRONG_SEARCH,
                TimingLevel.WORD, true, 0.99, 0L, true, false, "bbb");
        Resolution resolution = CatalogResolver.resolveWithIncumbent(
                Arrays.asList(challenger, incumbent), null, incumbent);
        assertEquals(challenger.candidateId, resolution.winner.candidateId);
    }

    @Test
    public void materialImprovementUpgradesTheWinner() {
        CatalogCandidate incumbent = cand(SourceId.LRCLIB, MatchMethod.STRONG_SEARCH,
                TimingLevel.LINE, true, 0.9, 0L, true, false, "aaa");
        CatalogCandidate challenger = cand(SourceId.APPLE, MatchMethod.EXACT_SPOTIFY_ID,
                TimingLevel.LINE, true, 0.9, 0L, true, false, "bbb");
        Resolution resolution = CatalogResolver.resolveWithIncumbent(
                Arrays.asList(incumbent, challenger), null, incumbent);
        assertEquals(challenger.candidateId, resolution.winner.candidateId);
    }

    @Test
    public void sameDigestKeepsTheIncumbentRow() {
        CatalogCandidate incumbent = cand(SourceId.AMLL, MatchMethod.STRONG_SEARCH,
                TimingLevel.LINE, true, 0.9, 0L, true, false, "same");
        CatalogCandidate reordered = cand(SourceId.AMLL, MatchMethod.STRONG_SEARCH,
                TimingLevel.LINE, true, 0.9, 0L, true, false, "same");
        Resolution resolution = CatalogResolver.resolveWithIncumbent(
                Arrays.asList(reordered, incumbent), null, incumbent);
        assertEquals(incumbent.candidateId, resolution.winner.candidateId);
    }

    @Test
    public void manualPinWinsOverBetterAutomatic() {
        CatalogCandidate pinned = cand(SourceId.LRCLIB, MatchMethod.STRONG_SEARCH,
                TimingLevel.UNSYNCED, true);
        CatalogCandidate better = cand(SourceId.APPLE, MatchMethod.EXACT_SPOTIFY_ID,
                TimingLevel.SYLLABLE, true);
        CatalogSelection manual = new CatalogSelection("track", SelectionMode.MANUAL,
                pinned.candidateId, SourceId.LRCLIB, "item", "");
        Resolution resolution = CatalogResolver.resolve(Arrays.asList(better, pinned), manual);
        assertEquals(pinned.candidateId, resolution.winner.candidateId);
        assertFalse(resolution.temporary);
        assertTrue(resolution.reason.startsWith("manual:"));
    }

    @Test
    public void missingManualPinFallsBackTemporarilyWithoutClearing() {
        CatalogCandidate best = cand(SourceId.APPLE, MatchMethod.EXACT_SPOTIFY_ID,
                TimingLevel.LINE, true);
        CatalogSelection manual = new CatalogSelection("track", SelectionMode.MANUAL,
                "gone|item|x", SourceId.APPLE, "item", "");
        Resolution resolution = CatalogResolver.resolve(Collections.singletonList(best), manual);
        assertEquals(best.candidateId, resolution.winner.candidateId);
        assertTrue(resolution.temporary);
        assertTrue(resolution.reason.startsWith("manual-missing-temporary:"));
    }

    @Test
    public void manualWithNoCandidatesResolvesEmpty() {
        CatalogSelection manual = new CatalogSelection("track", SelectionMode.MANUAL,
                "gone|item|x", SourceId.APPLE, "item", "");
        Resolution resolution = CatalogResolver.resolve(Collections.<CatalogCandidate>emptyList(),
                manual);
        assertNull(resolution.winner);
        assertTrue(resolution.temporary);
    }

    @Test
    public void capabilitiesBreakProviderTies() {
        CatalogCandidate plain = cand(SourceId.QQ, MatchMethod.STRONG_SEARCH, TimingLevel.WORD,
                true, 0.9, 0L, true, false, "aaa");
        CatalogCandidate rich = cand(SourceId.QQ, MatchMethod.STRONG_SEARCH, TimingLevel.WORD,
                true, 0.9, 0L, true, true, "bbb");
        assertEquals(rich.candidateId,
                CatalogResolver.resolve(Arrays.asList(plain, rich), null).winner.candidateId);
    }

    @Test
    public void timingHealthBeatsConfidence() {
        CatalogCandidate unhealthy = cand(SourceId.AMLL, MatchMethod.STRONG_SEARCH,
                TimingLevel.LINE, true, 0.99, 0L, false, false, "aaa");
        CatalogCandidate healthy = cand(SourceId.AMLL, MatchMethod.STRONG_SEARCH,
                TimingLevel.LINE, true, 0.5, 0L, true, false, "bbb");
        assertEquals(healthy.candidateId,
                CatalogResolver.resolve(Arrays.asList(unhealthy, healthy), null)
                        .winner.candidateId);
    }

    @Test
    public void confidenceThenDurationFitBreakRemainingTies() {
        CatalogCandidate confident = cand(SourceId.NETEASE, MatchMethod.STRONG_SEARCH,
                TimingLevel.WORD, true, 0.95, 5000L, true, false, "aaa");
        CatalogCandidate fitter = cand(SourceId.NETEASE, MatchMethod.STRONG_SEARCH,
                TimingLevel.WORD, true, 0.9, 100L, true, false, "bbb");
        List<CatalogCandidate> pair = Arrays.asList(fitter, confident);
        assertEquals(confident.candidateId, CatalogResolver.resolve(pair, null).winner.candidateId);
        CatalogCandidate close = cand(SourceId.NETEASE, MatchMethod.STRONG_SEARCH,
                TimingLevel.WORD, true, 0.9, 50L, true, false, "ccc");
        assertEquals(close.candidateId, CatalogResolver.resolve(
                Arrays.asList(fitter, close), null).winner.candidateId);
    }

    @Test
    public void providerOrderPrefersAppleOverNativeOnEqualRank() {
        CatalogCandidate nativeRow = cand(SourceId.SPOTIFY_NATIVE, MatchMethod.STRONG_SEARCH,
                TimingLevel.LINE, true);
        CatalogCandidate appleRow = cand(SourceId.APPLE, MatchMethod.STRONG_SEARCH,
                TimingLevel.LINE, true);
        assertEquals(appleRow.candidateId, CatalogResolver.resolve(
                Arrays.asList(nativeRow, appleRow), null).winner.candidateId);
    }

    @Test
    public void disabledProviderCannotWinAutomaticSelection() {
        CatalogCandidate apple = cand(SourceId.APPLE, MatchMethod.EXACT_SPOTIFY_ID,
                TimingLevel.SYLLABLE, true);
        CatalogCandidate spotify = cand(SourceId.SPOTIFY_NATIVE, MatchMethod.EXACT_SPOTIFY_ID,
                TimingLevel.LINE, true);

        Resolution result = CatalogResolver.resolveConfigured(Arrays.asList(apple, spotify),
                CatalogSelection.auto("track"), apple,
                Collections.singletonList(SourceId.SPOTIFY_NATIVE), false);

        assertEquals(spotify.candidateId, result.winner.candidateId);
    }

    @Test
    public void sourceOrderBeatsQualityRankingForAutomaticSelection() {
        CatalogCandidate apple = cand(SourceId.APPLE, MatchMethod.EXACT_SPOTIFY_ID,
                TimingLevel.SYLLABLE, true);
        CatalogCandidate spotify = cand(SourceId.SPOTIFY_NATIVE, MatchMethod.EXACT_SPOTIFY_ID,
                TimingLevel.LINE, true);

        Resolution result = CatalogResolver.resolveConfigured(Arrays.asList(apple, spotify),
                CatalogSelection.auto("track"), null,
                Arrays.asList(SourceId.SPOTIFY_NATIVE, SourceId.APPLE), true);

        assertEquals(spotify.candidateId, result.winner.candidateId);
    }

    @Test
    public void disabledManualPickRemainsReadable() {
        CatalogCandidate pinned = cand(SourceId.APPLE, MatchMethod.EXACT_SPOTIFY_ID,
                TimingLevel.LINE, true);
        CatalogSelection manual = new CatalogSelection("track", SelectionMode.MANUAL,
                pinned.candidateId, SourceId.APPLE, "item", pinned.canonicalDigest);

        Resolution result = CatalogResolver.resolveConfigured(Collections.singletonList(pinned),
                manual, null, Collections.singletonList(SourceId.SPOTIFY_NATIVE), false);

        assertEquals(pinned.candidateId, result.winner.candidateId);
    }

    @Test
    public void karaokeSubstitutionRanksBelowExactMappingSameTiming() {
        CatalogCandidate mapping = cand(SourceId.APPLE, MatchMethod.EXACT_PROVIDER_MAPPING,
                TimingLevel.LINE, true);
        CatalogCandidate karaoke = cand(SourceId.APPLE, MatchMethod.KARAOKE_SUBSTITUTION,
                TimingLevel.LINE, true);
        assertEquals(mapping.candidateId, CatalogResolver.resolve(
                Arrays.asList(karaoke, mapping), null).winner.candidateId);
    }

    @Test
    public void emptyCatalogResolvesToNull() {
        Resolution resolution =
                CatalogResolver.resolve(Collections.<CatalogCandidate>emptyList(), null);
        assertNull(resolution.winner);
        assertEquals("auto-empty", resolution.reason);
    }

    @Test
    public void reasonsNeverContainLyricBodies() {
        CatalogCandidate row = cand(SourceId.APPLE, MatchMethod.EXACT_SPOTIFY_ID,
                TimingLevel.SYLLABLE, true);
        String reason = CatalogResolver.resolve(Collections.singletonList(row), null).reason;
        assertTrue(reason.startsWith("auto:apple|syllable|exact_spotify_id|complete|"));
        assertTrue(reason.contains(row.candidateId));
    }
}
