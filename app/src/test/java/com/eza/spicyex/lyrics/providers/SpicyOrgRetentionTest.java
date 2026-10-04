package com.eza.spicyex.lyrics.providers;

import com.eza.spicyex.SpotifyTrack;
import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.lyrics.LyricsLine;
import com.eza.spicyex.lyrics.catalog.CatalogAdapters;
import com.eza.spicyex.lyrics.catalog.CatalogCandidate;
import com.eza.spicyex.lyrics.catalog.CatalogChange;
import com.eza.spicyex.lyrics.catalog.CatalogDecisions;
import com.eza.spicyex.lyrics.catalog.CatalogPolicy;
import com.eza.spicyex.lyrics.catalog.CatalogState;
import com.eza.spicyex.lyrics.catalog.CatalogResolver;
import com.eza.spicyex.lyrics.catalog.CatalogSelection;
import com.eza.spicyex.lyrics.catalog.CatalogSource.*;
import com.eza.spicyex.lyrics.catalog.LyricsCatalog;
import com.eza.spicyex.lyrics.session.CanonicalSourceCodec;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

public class SpicyOrgRetentionTest {
    private static final long DAY = 24L * 60 * 60 * 1000;
    private static final SpotifyTrack TRACK = new SpotifyTrack("Song", "Artist", "Album",
            "spotify:track:1QV6tiMFM6fSOKOGLMHYYg", 0, "", 0, null, 180000, false);

    private static LyricsDocument document(String origin, String provider, long fetchedAt) {
        LyricsDocument doc = new LyricsDocument();
        doc.fetchSource = origin;
        doc.provider = provider;
        doc.type = "Line";
        doc.spicyOrgFetchedAtMs = fetchedAt;
        LyricsLine line = new LyricsLine();
        line.text = "Hello";
        line.startMs = 1000;
        line.endMs = 2000;
        doc.lines.add(line);
        return doc;
    }

    private static CatalogCandidate candidate(SourceId source, LyricsDocument doc, long storedAt) {
        return CatalogAdapters.buildCandidate(source, TRACK, doc, MatchMethod.EXACT_SPOTIFY_ID,
                source.id, "", 1, storedAt);
    }

    @Test public void refreshAtTwentyOneDaysAndExpireAtThirtyDays() {
        long fetchedAt = 100000;
        assertFalse(SpicyOrgPolicy.refreshDueAt(fetchedAt, fetchedAt + 21 * DAY - 1));
        assertTrue(SpicyOrgPolicy.refreshDueAt(fetchedAt, fetchedAt + 21 * DAY));
        assertTrue(SpicyOrgPolicy.refreshDueAt(fetchedAt, fetchedAt + 21 * DAY + 1));
        assertFalse(SpicyOrgPolicy.expiredAt(fetchedAt, fetchedAt + 21 * DAY));
        assertFalse(SpicyOrgPolicy.expiredAt(fetchedAt, fetchedAt + 30 * DAY - 1));
        assertTrue(SpicyOrgPolicy.expiredAt(fetchedAt, fetchedAt + 30 * DAY));
        assertTrue(SpicyOrgPolicy.expiredAt(fetchedAt, fetchedAt + 30 * DAY + 1));
        assertTrue(SpicyOrgPolicy.expiredAt(0, fetchedAt));
        assertTrue(SpicyOrgPolicy.expiredAt(-1, fetchedAt));
        assertTrue(SpicyOrgPolicy.expiredAt(fetchedAt + 1, fetchedAt));
    }

    @Test public void providerCreditNeverChangesOrgReplayOrSelectionExpiry() {
        long now = System.currentTimeMillis();
        for (String provider : Arrays.asList("Spicy Lyrics", "Apple Music", "Spotify (Musixmatch)")) {
            LyricsDocument doc = document("spicy_org", provider, now - 31 * DAY);
            CatalogCandidate org = candidate(SourceId.SPICY_ORG, doc, now);
            assertNotNull(org);
            assertEquals(doc.spicyOrgFetchedAtMs, org.fetchedAtMs);
            assertFalse(org.hasValidProviderTiming());
            assertNull(LyricsCatalog.decode(org));
            assertNull(CanonicalSourceCodec.decode(CanonicalSourceCodec.encode(doc, 1, "d", now)));
        }
    }

    @Test public void recentReplayAndRestorageDoNotResetOrgClock() {
        long now = System.currentTimeMillis();
        LyricsDocument doc = document("spicy_org", "Apple Music", now - 20 * DAY);
        CanonicalSourceCodec.Record restored = CanonicalSourceCodec.decode(
                CanonicalSourceCodec.encode(LyricsDocument.copyOf(doc), 1, "d", now));
        assertNotNull(restored);
        assertEquals(doc.spicyOrgFetchedAtMs, restored.document.spicyOrgFetchedAtMs);
        CatalogCandidate org = candidate(SourceId.SPICY_ORG, restored.document, now);
        assertEquals(doc.spicyOrgFetchedAtMs, org.fetchedAtMs);
        assertTrue(org.hasValidProviderTiming());
        assertNotNull(LyricsCatalog.decode(org));
    }

    @Test public void missingOrFutureOrgTimeCannotReplay() {
        long now = System.currentTimeMillis();
        for (long fetchedAt : new long[]{0, now + DAY}) {
            LyricsDocument doc = document("spicy_org", "Spotify (Musixmatch)", fetchedAt);
            assertNull(CanonicalSourceCodec.decode(CanonicalSourceCodec.encode(doc, 1, "d", now)));
            CatalogCandidate org = candidate(SourceId.SPICY_ORG, doc, now);
            assertFalse(org.hasValidProviderTiming());
            assertTrue(SpicyOrgPolicy.needsRefresh(doc, now));
            CatalogChange invalid = CatalogDecisions.providerSuccess(CatalogState.empty(org.trackId),
                    new CatalogPolicy(Collections.singletonList(SourceId.SPICY_ORG), false), org, null, now);
            assertFalse(invalid.accepted);
            assertTrue(invalid.writesNothing());
        }
    }

    @Test public void expiredManualOrgSeatFallsBackWithoutChangingItsPinOrOtherCandidates() {
        long now = System.currentTimeMillis();
        CatalogCandidate org = candidate(SourceId.SPICY_ORG,
                document("spicy_org", "Apple Music", now - 31 * DAY), now);
        CatalogCandidate apple = candidate(SourceId.APPLE,
                document("apple_music", "Apple Music", 0), now - 90 * DAY);
        CatalogSelection pin = new CatalogSelection(org.trackId, SelectionMode.MANUAL,
                org.candidateId, org.sourceId, org.providerItemId, org.canonicalDigest);
        CatalogResolver.Resolution result = CatalogResolver.resolve(Arrays.asList(org, apple), pin);
        assertEquals(apple, result.winner);
        assertTrue(result.temporary);
        assertEquals(org.candidateId, pin.candidateId);
        assertNotNull(LyricsCatalog.decode(apple));
    }

    @Test public void networkRefreshFailureKeepsGraceResponseAndOriginalClock() {
        long now = System.currentTimeMillis();
        LyricsDocument doc = document("spicy_org", "Apple Music", now - 25 * DAY);
        CatalogCandidate org = candidate(SourceId.SPICY_ORG, doc, now);
        CatalogState state = new CatalogState(org.trackId, Collections.singletonList(org),
                null, CatalogSelection.auto(org.trackId), null, true);
        CatalogPolicy policy = new CatalogPolicy(Collections.singletonList(SourceId.SPICY_ORG), false);
        CatalogChange failed = CatalogDecisions.providerFailure(state, policy,
                SourceId.SPICY_ORG, ProviderStatus.TRANSIENT_ERROR, now);
        assertTrue(SpicyOrgPolicy.needsRefresh(doc, now));
        assertFalse(SpicyOrgPolicy.expires(doc, now));
        assertEquals(org, failed.resolution.winner);
        assertTrue(failed.deleteCandidateIds.isEmpty());
        assertTrue(failed.putCandidates.isEmpty());
        assertEquals(doc.spicyOrgFetchedAtMs, org.fetchedAtMs);
        assertNotNull(LyricsCatalog.decode(org));
    }

    @Test public void successfulIdenticalResponseRefreshReplacesRowAndResetsClock() {
        long now = System.currentTimeMillis();
        CatalogCandidate old = candidate(SourceId.SPICY_ORG,
                document("spicy_org", "Apple Music", now - 25 * DAY), now);
        CatalogCandidate fresh = candidate(SourceId.SPICY_ORG,
                document("spicy_org", "Apple Music", now), now);
        CatalogState state = new CatalogState(old.trackId, Collections.singletonList(old),
                null, null, null, true);
        CatalogPolicy policy = new CatalogPolicy(Collections.singletonList(SourceId.SPICY_ORG), false);
        CatalogChange success = CatalogDecisions.providerSuccess(state, policy, fresh, null, now);
        assertEquals(old.candidateId, fresh.candidateId);
        assertEquals("refreshed", success.outcome);
        assertEquals(Collections.singletonList(fresh), success.putCandidates);
        assertEquals(fresh, success.resolution.winner);
        assertEquals(now, fresh.fetchedAtMs);
        assertFalse(SpicyOrgPolicy.refreshDueAt(fresh.fetchedAtMs, now));
        assertEquals(now, LyricsCatalog.decode(fresh).document.spicyOrgFetchedAtMs);
    }

    @Test public void freshChangedOrgResponseRetiresOldVersionAndFollowsItsManualPin() {
        long now = System.currentTimeMillis();
        CatalogCandidate old = candidate(SourceId.SPICY_ORG,
                document("spicy_org", "Apple Music", now - 25 * DAY), now);
        LyricsDocument replacement = document("spicy_org", "Apple Music", now);
        replacement.lines.get(0).text = "Changed lyrics";
        CatalogCandidate fresh = candidate(SourceId.SPICY_ORG, replacement, now);
        CatalogCandidate apple = candidate(SourceId.APPLE,
                document("apple_music", "Apple Music", 0), now - 90 * DAY);
        CatalogCandidate qq = candidate(SourceId.QQ,
                document("qq", "QQ Music", 0), now - 90 * DAY);
        CatalogSelection pin = new CatalogSelection(old.trackId, SelectionMode.MANUAL,
                old.candidateId, old.sourceId, old.providerItemId, old.canonicalDigest);
        CatalogState state = new CatalogState(old.trackId, Arrays.asList(old, apple, qq),
                null, pin, null, true);
        CatalogPolicy policy = new CatalogPolicy(Arrays.asList(SourceId.SPICY_ORG, SourceId.APPLE, SourceId.QQ), false);
        CatalogChange success = CatalogDecisions.providerSuccess(state, policy, fresh, null, now);
        assertTrue(success.accepted);
        assertEquals(Collections.singletonList(old.candidateId), success.deleteCandidateIds);
        assertEquals(Collections.singletonList(fresh), success.putCandidates);
        assertEquals(SelectionMode.MANUAL, success.selection.mode);
        assertEquals(fresh.candidateId, success.selection.candidateId);
        assertEquals(fresh, success.resolution.winner);
        assertFalse(success.resolution.temporary);
        assertNotNull(LyricsCatalog.decode(apple));
        assertNotNull(LyricsCatalog.decode(qq));
    }

    @Test public void olderOrgDeliveryCannotReplaceNewerResponseEvenWithSameContent() {
        long now = System.currentTimeMillis();
        CatalogCandidate fresh = candidate(SourceId.SPICY_ORG,
                document("spicy_org", "Apple Music", now - DAY), now);
        CatalogState state = new CatalogState(fresh.trackId, Collections.singletonList(fresh),
                null, null, null, true);
        CatalogPolicy policy = new CatalogPolicy(Collections.singletonList(SourceId.SPICY_ORG), false);
        for (String text : Arrays.asList("Hello", "Stale changed lyrics")) {
            LyricsDocument oldDoc = document("spicy_org", "Apple Music", now - 25 * DAY);
            oldDoc.lines.get(0).text = text;
            CatalogChange stale = CatalogDecisions.providerSuccess(state, policy,
                    candidate(SourceId.SPICY_ORG, oldDoc, now), null, now);
            assertFalse(stale.accepted);
            assertTrue(stale.writesNothing());
            assertEquals("stale-org-response", stale.outcome);
            assertEquals(fresh, stale.resolution.winner);
        }
    }

    @Test public void otherProviderClocksAndReplayRemainUnrestricted() {
        long now = System.currentTimeMillis();
        for (SourceId source : SourceId.values()) {
            if (source == SourceId.SPICY_ORG) continue;
            LyricsDocument doc = document(source.id, "Apple Music", 0);
            CatalogCandidate other = candidate(source, doc, now - 90 * DAY);
            assertFalse(SpicyOrgPolicy.expires(doc, now));
            assertFalse(SpicyOrgPolicy.needsRefresh(doc, now));
            assertEquals(now - 90 * DAY, other.fetchedAtMs);
            assertTrue(other.hasValidProviderTiming());
            assertNotNull(LyricsCatalog.decode(other));
        }
    }
}
