package com.eza.spicyex.lyrics.catalog;

import com.eza.spicyex.SpotifyTrack;
import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.lyrics.LyricsLine;
import com.eza.spicyex.lyrics.catalog.CatalogSource.*;
import com.eza.spicyex.lyrics.session.CanonicalSourceCodec;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class CatalogNativeContentTest {
    private static LyricsDocument document(String... texts) {
        LyricsDocument doc = new LyricsDocument();
        doc.trackId = "track";
        doc.fetchSource = "spotify_native_model";
        doc.type = "Static";
        for (String text : texts) { LyricsLine line = new LyricsLine(); line.text = text; doc.lines.add(line); }
        return doc;
    }
    private static LyricsDocument junk() {
        return document("fragment_container_bottom_overlap_touch_event_consumer,fragment_container_inset_bottom_spacing",
                "status_bar_placeholder,bannerContainer", "unobstructed_main_content_top_barrier",
                "fragment_container_bottom_overlap_barrier",
                "now_playing_view_attachments_container,now_playing_view_container,navigation_bar,limited_experience_indicator_container,navigation_bar_window_insets_space,thinking_agent_container",
                "limited_experience_indicator_container,navigation_bar_window_insets_space",
                "now_playing_view_attachments_container,now_playing_view_container,navigation_bar,limited_experience_indicator_container,navigation_bar_window_insets_space",
                "ime_window_insets_space,fragment_container_bottom_overlap_touch_event_consumer");
    }
    private static CatalogCandidate candidate(SourceId source, LyricsDocument doc, String id) {
        return new CatalogCandidate(id, "track", source, "track", MatchMethod.EXACT_SPOTIFY_ID,
                0.8, 0, TimingLevel.UNSYNCED, true, true, false, false, false, false, false,
                id, CanonicalSourceCodec.encode(doc, 1, id, 1000), "[]", new byte[0], 2, 1, 1000);
    }
    private static CatalogPolicy policy() {
        return new CatalogPolicy(Arrays.asList(SourceId.SPOTIFY_NATIVE, SourceId.LRCLIB), false);
    }
    @Test public void cachedLayoutCannotDecodeRenderOrHoldAutoSeat() {
        CatalogCandidate bad = candidate(SourceId.SPOTIFY_NATIVE, junk(), "bad");
        CatalogCandidate good = candidate(SourceId.LRCLIB, document("A real lyric"), "good");
        assertNull(LyricsCatalog.decode(bad));
        assertEquals(good, CatalogResolver.resolveWithIncumbent(Arrays.asList(bad, good),
                CatalogSelection.auto("track"), bad).winner);
        assertNull(CatalogDecisions.render(new CatalogState("track", Collections.singletonList(bad),
                null, null, null, true), policy()).winner);
    }
    @Test public void manualPinAndPayloadStayStoredWhileInvalidLyricsUseTemporaryFallback() {
        CatalogCandidate bad = candidate(SourceId.SPOTIFY_NATIVE, junk(), "bad");
        CatalogCandidate good = candidate(SourceId.LRCLIB, document("A real lyric"), "good");
        CatalogSelection pin = new CatalogSelection("track", SelectionMode.MANUAL, "bad",
                SourceId.SPOTIFY_NATIVE, "track", "bad");
        CatalogState state = new CatalogState("track", Arrays.asList(bad, good), null, pin, null, true);
        CatalogChange change = CatalogDecisions.reconcile(state, policy(), null);
        assertEquals(good, change.resolution.winner);
        assertTrue(change.resolution.temporary);
        assertNull(change.selection);
        assertTrue(change.deleteCandidateIds.isEmpty());
        assertEquals(pin, state.selection);
        assertEquals(2, state.candidates.size());
        for (CatalogPickerModel.Row row : CatalogPickerModel.build(state.candidates, state.statuses(),
                pin, CatalogDecisions.autoResolution(state, policy()), policy())) {
            assertNotEquals("bad", row.candidateId);
        }
    }
    @Test public void invalidAvailableNativeCacheCanBeFetchedAgain() {
        CatalogCandidate bad = candidate(SourceId.SPOTIFY_NATIVE, junk(), "bad");
        CatalogState state = new CatalogState("track", Collections.singletonList(bad),
                Collections.singletonMap(SourceId.SPOTIFY_NATIVE, new ProviderRecord(SourceId.SPOTIFY_NATIVE,
                        ProviderStatus.AVAILABLE, 1000, 1000, 1000, 0)), null, null, true);
        AcquisitionPlanner.Plan plan = AcquisitionPlanner.plan(state, policy(),
                CatalogDecisions.render(state, policy()), 2000, true);
        assertTrue(plan.fetches());
        assertEquals(Collections.singletonList(SourceId.SPOTIFY_NATIVE), plan.scope.sources);
    }
    @Test public void adapterRefusesNewNativeLayoutDocument() {
        SpotifyTrack track = new SpotifyTrack("Song", "Artist", "Album", "spotify:track:track",
                0, "", 0, null, 180000, false);
        assertNull(CatalogAdapters.buildCandidate(SourceId.SPOTIFY_NATIVE, track, junk(),
                MatchMethod.EXACT_SPOTIFY_ID, "track", "", 1, 1000));
    }
    @Test public void genuineUnsyncedAndIdentifierLikeLyricsRemainUsable() {
        CatalogCandidate nativeLyrics = candidate(SourceId.SPOTIFY_NATIVE, document("love_me,hold_me",
                "I see now_playing_view_container in a dream", "under_score"), "good");
        assertNotNull(LyricsCatalog.decode(nativeLyrics));
        assertEquals(nativeLyrics, CatalogDecisions.render(new CatalogState("track",
                Collections.singletonList(nativeLyrics), null, null, null, true), policy()).winner);
        assertNotNull(LyricsCatalog.decode(candidate(SourceId.APPLE, junk(), "apple")));
    }
}
