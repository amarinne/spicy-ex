package com.eza.spicyex.lyrics.catalog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.eza.spicyex.SpotifyTrack;
import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.lyrics.LyricsLine;
import com.eza.spicyex.lyrics.catalog.CatalogSource.MatchMethod;
import com.eza.spicyex.lyrics.catalog.CatalogSource.ProviderStatus;
import com.eza.spicyex.lyrics.catalog.CatalogSource.SourceId;
import com.eza.spicyex.testsupport.FakeAndroidContext;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * F7: an unreadable catalog is an explicit failure, not a durable empty catalog, and snapshot
     * reads hide expired Spicy.org rows without deleting them.
 */
public class CatalogReadFailureTest {
    private static final long DAY = 24L * 60 * 60 * 1000;
    private static final String ID = "1QV6tiMFM6fSOKOGLMHYYg";
    private static final SpotifyTrack TRACK = new SpotifyTrack("Song", "Artist", "Album",
            "spotify:track:" + ID, 0, "", 0, null, 180000, false);

    @Test public void unreadableStorageIsNotAnEmptyCatalog() {
        FakeAndroidContext context = new FakeAndroidContext();
        CatalogStore.Read failed = CatalogStore.read(context, ID,
                (ctx, trackId) -> {
                    throw new IllegalStateException("disk I/O");
                });
        assertFalse(failed.readable);
        assertEquals(ID, failed.state.trackId);
        assertFalse(failed.state.hasHistory());

        CatalogStore.Read empty = CatalogStore.read(context, ID,
                (ctx, trackId) -> CatalogState.empty(trackId));
        assertTrue(empty.readable);
        assertFalse(empty.state.hasHistory());
        assertFalse(LyricsCatalog.importEligible(failed));
        assertTrue(LyricsCatalog.importEligible(empty));
    }

    @Test public void successfulReadRecoversTheStoredSeat() {
        FakeAndroidContext context = new FakeAndroidContext();
        CatalogState stored = new CatalogState(ID,
                Collections.singletonList(apple(now() - 90 * DAY)), null, null, null, true);
        CatalogStore.Read recovered = CatalogStore.read(context, ID,
                (ctx, trackId) -> stored);
        assertTrue(recovered.readable);
        assertTrue(recovered.state.hasHistory());
        assertEquals(1, recovered.state.candidates.size());
        assertFalse(LyricsCatalog.importEligible(recovered));
    }

    @Test public void unreadableViewHasNoDurableAbsencePlan() {
        CatalogPolicy policy = new CatalogPolicy(
                Arrays.asList(SourceId.APPLE, SourceId.SPOTIFY_NATIVE), false);
        LyricsCatalog.View failed = LyricsCatalog.view(ID, CatalogState.empty(ID), policy,
                now(), true, true, false, 1L, "", 90000);
        assertFalse(failed.readable);
        assertFalse(failed.durable);
        assertNull(failed.plan);
        assertNull(failed.document);

        LyricsCatalog.View empty = LyricsCatalog.view(ID, CatalogState.empty(ID), policy,
                now(), true, true, true, 1L, "", 90000);
        assertTrue(empty.readable);
        assertTrue(empty.durable);
        assertNotNull(empty.plan);
        assertTrue(empty.plan.fetches());
        assertEquals(Collections.singletonList(SourceId.APPLE), empty.plan.scope.sources);
    }

    @Test public void readableSnapshotRendersItsSeatAfterAFailure() {
        CatalogPolicy policy = new CatalogPolicy(Collections.singletonList(SourceId.APPLE), false);
        CatalogState stored = new CatalogState(ID,
                Collections.singletonList(apple(now() - 90 * DAY)), null, null, null, true);
        LyricsCatalog.View recovered = LyricsCatalog.view(ID, stored, policy, now(), true,
                true, true, 2L, "", 90000);
        assertTrue(recovered.readable);
        assertTrue(recovered.durable);
        assertNotNull(recovered.document);
        assertEquals(SourceId.APPLE, recovered.seat().sourceId);
    }

    @Test public void readHidesExpiredOrgRowsWithoutMutatingTheStoredSnapshot() {
        long now = now();
        CatalogCandidate expiredOrg = org(now - 30 * DAY);
        CatalogCandidate liveOrg = org(now - 30 * DAY + 1);
        CatalogCandidate oldApple = apple(now - 90 * DAY);
        Map<SourceId, ProviderRecord> providers = new EnumMap<>(SourceId.class);
        providers.put(SourceId.SPICY_ORG, ProviderRecord.notChecked(SourceId.SPICY_ORG).succeeded(now));
        providers.put(SourceId.APPLE, ProviderRecord.notChecked(SourceId.APPLE).succeeded(now));

        CatalogState onlyExpired = new CatalogState(ID,
                Collections.singletonList(expiredOrg), providers, null, null, true);
        CatalogState visible = CatalogStore.visibleState(onlyExpired, now);
        assertTrue(visible.candidates.isEmpty());
        assertEquals(1, onlyExpired.candidates.size());
        assertEquals(ProviderStatus.NEEDS_REFRESH, visible.provider(SourceId.SPICY_ORG).status);
        assertEquals(ProviderStatus.AVAILABLE, onlyExpired.provider(SourceId.SPICY_ORG).status);

        CatalogState mixed = new CatalogState(ID,
                Arrays.asList(expiredOrg, liveOrg, oldApple), providers, null, null, true);
        CatalogState mixedVisible = CatalogStore.visibleState(mixed, now);
        assertEquals(2, mixedVisible.candidates.size());
        assertEquals(3, mixed.candidates.size());
        assertEquals(ProviderStatus.AVAILABLE, mixedVisible.provider(SourceId.SPICY_ORG).status);
        assertTrue(mixedVisible.candidates.contains(liveOrg));
        assertTrue(mixedVisible.candidates.contains(oldApple));
    }

    @Test public void directCandidateReadsHideExpiredOrgWithoutHidingOtherSources() {
        long now = now();
        assertNull(CatalogStore.visibleCandidate(org(now - 30 * DAY), now));
        assertNull(CatalogStore.visibleCandidate(null, now));
        CatalogCandidate live = org(now - 30 * DAY + 1);
        CatalogCandidate oldApple = apple(now - 90 * DAY);
        assertTrue(live == CatalogStore.visibleCandidate(live, now));
        assertTrue(oldApple == CatalogStore.visibleCandidate(oldApple, now));
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    private static LyricsDocument document(String origin, long fetchedAt) {
        LyricsDocument doc = new LyricsDocument();
        doc.trackId = ID;
        doc.fetchSource = origin;
        doc.provider = "Fixture";
        doc.type = "Line";
        doc.spicyOrgFetchedAtMs = fetchedAt;
        LyricsLine line = new LyricsLine();
        line.text = "Hello";
        line.startMs = 1000;
        line.endMs = 2000;
        doc.lines.add(line);
        return doc;
    }

    private static CatalogCandidate org(long fetchedAt) {
        CatalogCandidate candidate = CatalogAdapters.buildCandidate(SourceId.SPICY_ORG, TRACK,
                document("spicy_org", fetchedAt), MatchMethod.EXACT_SPOTIFY_ID, ID, "", 1,
                System.currentTimeMillis());
        assertNotNull(candidate);
        return candidate;
    }

    private static CatalogCandidate apple(long storedAt) {
        CatalogCandidate candidate = CatalogAdapters.buildCandidate(SourceId.APPLE, TRACK,
                document("apple_music", 0), MatchMethod.EXACT_SPOTIFY_ID, ID, "", 1, storedAt);
        assertNotNull(candidate);
        return candidate;
    }
}
