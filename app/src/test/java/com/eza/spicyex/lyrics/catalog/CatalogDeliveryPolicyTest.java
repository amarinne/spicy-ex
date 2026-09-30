package com.eza.spicyex.lyrics.catalog;

import static org.junit.Assert.*;

import com.eza.spicyex.SpotifyTrack;
import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.lyrics.LyricsLine;
import com.eza.spicyex.lyrics.catalog.CatalogSource.MatchMethod;
import com.eza.spicyex.lyrics.catalog.CatalogSource.SourceId;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

public class CatalogDeliveryPolicyTest {
    private static final SpotifyTrack TRACK = new SpotifyTrack(
            "Song", "Artist", "Album", "spotify:track:abc123", 0, "", 0, null, 180000, false);

    private static LyricsDocument document() {
        LyricsDocument doc = new LyricsDocument();
        doc.trackId = "abc123";
        doc.fetchSource = "qq_music";
        doc.type = "Line";
        LyricsLine line = new LyricsLine();
        line.text = "hello";
        line.startMs = 1000;
        line.endMs = 2000;
        doc.lines.add(line);
        return doc;
    }

    @Test public void rejectedAdapterItemCannotBeRecommittedUnderAnEmptyIdentity() {
        for (SourceId source : Arrays.asList(SourceId.QQ, SourceId.NETEASE, SourceId.AMLL)) {
            LyricsDocument doc = document();
            // Exercise the adapter write boundary with unavailable storage. Its exact attempted
            // identity must survive refusal/failure and the coordinator's defensive copy.
            assertFalse(CatalogAdapters.recordSuccess(null, source, TRACK, doc,
                    MatchMethod.EXACT_PROVIDER_MAPPING, "provider-item-42", "", 1));
            CatalogDelivery attempted = doc.catalogDelivery;
            assertNotNull(attempted);
            assertEquals(source, attempted.source);
            assertEquals("provider-item-42", attempted.providerItemId);
            LyricsDocument delivered = LyricsDocument.copyOf(doc);
            assertFalse(CatalogAdapters.commitDelivered(null, TRACK, delivered));
            assertSame("The session must not attempt a second inferred write",
                    attempted, delivered.catalogDelivery);

            CatalogState empty = CatalogState.empty("abc123");
            CatalogState rejected = new CatalogState(empty.trackId, empty.candidates,
                    empty.providers, empty.selection, Collections.singleton(
                    CatalogState.rejectionKey(source, "provider-item-42")), false);
            CatalogPolicy policy = new CatalogPolicy(Collections.singletonList(source), false);
            CatalogCandidate candidate = CatalogAdapters.buildCandidate(source, TRACK, doc,
                    MatchMethod.EXACT_PROVIDER_MAPPING, "provider-item-42", "", 1, 1000);
            assertNotNull(candidate);
            CatalogChange refusal = CatalogDecisions.providerSuccess(rejected, policy,
                    candidate, CatalogTrack.of(TRACK, 1000), 1000);
            assertTrue(refusal.putCandidates.isEmpty());
            assertEquals("rejected-item", refusal.outcome);
            assertTrue(CatalogAdapters.isRefusedFallback(policy, rejected, "abc123", delivered));
            assertFalse(CatalogAdapters.isRefusedFallback(policy, empty, "abc123", delivered));
        }
    }

    @Test public void storedDisabledDeliveryStillCannotServeAsFallback() {
        LyricsDocument doc = document();
        CatalogPolicy disabled = new CatalogPolicy(Collections.singletonList(SourceId.LRCLIB), false);
        CatalogState state = CatalogState.empty("abc123");
        CatalogCandidate candidate = CatalogAdapters.buildCandidate(SourceId.QQ, TRACK, doc,
                MatchMethod.STRONG_SEARCH, "item", "", 1, 1000);
        assertNotNull(candidate);
        CatalogChange stored = CatalogDecisions.providerSuccess(state, disabled, candidate,
                CatalogTrack.of(TRACK, 1000), 1000);
        assertTrue(stored.accepted);
        assertEquals(1, stored.putCandidates.size());
        assertNull("Storage may accept a disabled source but Auto must not seat it",
                stored.resolution.winner);
        doc.catalogCandidateId = candidate.candidateId;
        doc.catalogDelivery = new CatalogDelivery(SourceId.QQ, "item", MatchMethod.STRONG_SEARCH, true);
        LyricsDocument delivered = LyricsDocument.copyOf(doc);
        assertTrue(CatalogAdapters.commitDelivered(null, TRACK, delivered));
        assertTrue(CatalogAdapters.isRefusedFallback(
                disabled, state, "abc123", delivered));
    }

    @Test public void karaokeProvenanceSurvivesFailedStorageAndRespectsTheCurrentPolicy() {
        LyricsDocument doc = document();
        assertFalse(CatalogAdapters.recordSuccess(null, SourceId.QQ, TRACK, doc,
                MatchMethod.KARAOKE_SUBSTITUTION, "original-song", "", 1));
        LyricsDocument delivered = LyricsDocument.copyOf(doc);
        assertTrue(CatalogAdapters.isRefusedFallback(
                new CatalogPolicy(Collections.singletonList(SourceId.QQ), false, false),
                CatalogState.empty("abc123"), "abc123", delivered));
        assertFalse(CatalogAdapters.isRefusedFallback(
                new CatalogPolicy(Collections.singletonList(SourceId.QQ), false, true),
                CatalogState.empty("abc123"), "abc123", delivered));
    }
}
