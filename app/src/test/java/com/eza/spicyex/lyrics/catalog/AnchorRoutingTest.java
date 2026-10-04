package com.eza.spicyex.lyrics.catalog;

import static org.junit.Assert.*;
import com.eza.spicyex.lyrics.catalog.CatalogSource.*;
import com.eza.spicyex.lyrics.session.LyricsSourcePreferences;
import org.junit.Test;
import java.util.*;

public class AnchorRoutingTest {
    private static CatalogCandidate candidate(SourceId source, TimingLevel timing, boolean complete,
                                               String digest) {
        return new CatalogCandidate(source.id + digest, "track1", source, "item",
                MatchMethod.EXACT_SPOTIFY_ID, .9, 0, timing, complete, true, false, false,
                false, false, true, digest, "", "[]", null, 1, 1, System.currentTimeMillis());
    }
    private static CatalogResolver.Resolution auto(List<CatalogCandidate> candidates,
                                                   CatalogCandidate incumbent) {
        return CatalogResolver.resolveConfigured(candidates, null, incumbent,
                Arrays.asList(SourceId.QQ, SourceId.SPOTIFY_NATIVE, SourceId.APPLE,
                        SourceId.SPICY_ORG, SourceId.NETEASE), false);
    }
    @Test public void routerLineAndStaticKeepSeatAgainstWordDonorsAndNative() {
        for (TimingLevel timing : new TimingLevel[]{TimingLevel.LINE, TimingLevel.UNSYNCED}) {
            CatalogCandidate org = candidate(SourceId.SPICY_ORG, timing, true, "org");
            CatalogCandidate qq = candidate(SourceId.QQ, TimingLevel.WORD, true, "qq");
            CatalogCandidate spotify = candidate(SourceId.SPOTIFY_NATIVE, TimingLevel.SYLLABLE, true, "spotify");
            assertSame(org, auto(Arrays.asList(qq, spotify, org), qq).winner);
        }
    }
    @Test public void partialAppleRemainsAnchorAgainstCompleteWordDonor() {
        CatalogCandidate apple = candidate(SourceId.APPLE, TimingLevel.LINE, false, "apple");
        CatalogCandidate qq = candidate(SourceId.QQ, TimingLevel.WORD, true, "qq");
        assertSame(apple, auto(Arrays.asList(qq, apple), qq).winner);
    }
    @Test public void equalDigestCannotHoldLowerSourceWhenPrimaryArrives() {
        CatalogCandidate apple = candidate(SourceId.APPLE, TimingLevel.LINE, true, "same");
        CatalogCandidate qq = candidate(SourceId.QQ, TimingLevel.WORD, true, "same");
        assertSame(apple, auto(Arrays.asList(qq, apple), qq).winner);
    }
    @Test public void nativeAnchorWinsBeforeDonorsWhenOtherPrimariesAbsent() {
        CatalogCandidate spotify = candidate(SourceId.SPOTIFY_NATIVE, TimingLevel.LINE, true, "native");
        CatalogCandidate qq = candidate(SourceId.QQ, TimingLevel.WORD, true, "qq");
        assertSame(spotify, auto(Arrays.asList(qq, spotify), qq).winner);
    }
    @Test public void sourceOrderAndManualPinsStillSelectOwnerChoice() {
        CatalogCandidate apple = candidate(SourceId.APPLE, TimingLevel.LINE, true, "apple");
        CatalogCandidate qq = candidate(SourceId.QQ, TimingLevel.WORD, true, "qq");
        assertSame(qq, CatalogResolver.resolveConfigured(Arrays.asList(qq, apple), null, null,
                Arrays.asList(SourceId.QQ, SourceId.APPLE), true).winner);
        CatalogSelection manual = new CatalogSelection("track1", SelectionMode.MANUAL,
                qq.candidateId, SourceId.QQ, "item", "qq");
        assertSame(qq, CatalogResolver.resolveConfigured(Arrays.asList(qq, apple), manual, null,
                Collections.singletonList(SourceId.APPLE), false).winner);
    }
    @Test public void missingManualPinUsesConfiguredEnabledAnchorAsTemporaryFallback() {
        CatalogCandidate apple = candidate(SourceId.APPLE, TimingLevel.LINE, true, "apple");
        CatalogCandidate qq = candidate(SourceId.QQ, TimingLevel.WORD, true, "qq");
        CatalogSelection missing = new CatalogSelection("track1", SelectionMode.MANUAL,
                "removed", SourceId.SPICY_ORG, "item", "missing");
        CatalogResolver.Resolution fallback = CatalogResolver.resolveConfigured(
                Arrays.asList(qq, apple), missing, qq, Collections.singletonList(SourceId.APPLE), false);
        assertSame(apple, fallback.winner);
        assertTrue(fallback.temporary);
        assertTrue(fallback.reason.startsWith("manual-missing-temporary:"));
    }
    @Test public void freshDefaultsPutUnconfiguredRouterFirstButEffectiveSourcesRemainPrimaryAndLrc() {
        List<LyricsSourcePreferences.Source> order = LyricsSourcePreferences.defaultOrder();
        assertEquals(LyricsSourcePreferences.Source.SPICY, order.get(0));
        List<LyricsSourcePreferences.Source> enabled = new ArrayList<>();
        for (LyricsSourcePreferences.Source source : order)
            if (LyricsSourcePreferences.enabledByDefault(source)) enabled.add(source);
        assertEquals(Arrays.asList(LyricsSourcePreferences.Source.APPLE_MUSIC,
                LyricsSourcePreferences.Source.SPOTIFY, LyricsSourcePreferences.Source.LRCLIB), enabled);
    }
}
