package com.eza.spicyex.lyrics.catalog;

import static org.junit.Assert.*;
import com.eza.spicyex.SpotifyTrack;
import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.lyrics.LyricsLine;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

public class CatalogNativeRepairTest {
    private static final CatalogPolicy POLICY = new CatalogPolicy(
            Collections.singletonList(CatalogSource.SourceId.SPOTIFY_NATIVE), false);

    private static CatalogCandidate candidate(String language, String source, String text, long start) {
        LyricsDocument doc = new LyricsDocument();
        doc.trackId = "track1";
        doc.language = language;
        doc.fetchSource = source;
        doc.provider = "Spotify (through Musixmatch)";
        doc.type = "Line";
        if ("unknown".equals(language)) {
            doc.selectedSource = "Spotify";
            doc.selectionMode = "strict";
            doc.selectionOverride = "Spotify";
        }
        LyricsLine line = new LyricsLine();
        line.text = text;
        line.startMs = start;
        line.endMs = 4000;
        doc.lines.add(line);
        SpotifyTrack track = new SpotifyTrack("Song", "Artist", "Album",
                "spotify:track:track1", 0, "", 0, null, 180000, false);
        return CatalogAdapters.buildCandidate(CatalogSource.SourceId.SPOTIFY_NATIVE, track, doc,
                CatalogSource.MatchMethod.EXACT_SPOTIFY_ID, "track1", "", 1, 1);
    }

    @Test public void repairsPartialDuplicateAndMovesManualPinAtomically() {
        CatalogCandidate partial = candidate("unknown", "spotify_native_model:LyricsResponse#w", "Line", 1000);
        CatalogCandidate rich = candidate("en", "spotify_native_model:ColorLyricsResponse#p", "Line", 1000);
        CatalogCandidate fresh = candidate("en", "spotify_native_model", "Line", 1000);
        CatalogSelection pin = new CatalogSelection("track1", CatalogSource.SelectionMode.MANUAL,
                partial.candidateId, partial.sourceId, partial.providerItemId, partial.canonicalDigest);
        CatalogState old = new CatalogState("track1", Arrays.asList(partial, rich),
                Collections.emptyMap(), pin, Collections.emptySet(), true);
        CatalogChange change = CatalogDecisions.providerSuccess(old, POLICY, fresh, null, 2);
        CatalogState repaired = CatalogDecisionsTest.apply(old, change);
        assertEquals(Collections.singletonList(partial.candidateId), change.deleteCandidateIds);
        assertEquals(1, repaired.candidates.size());
        assertEquals(CatalogSource.SelectionMode.MANUAL, repaired.selection.mode);
        assertEquals(fresh.candidateId, repaired.selection.candidateId);
        assertEquals(fresh.candidateId, change.resolution.winner.candidateId);
    }

    @Test public void keepsDifferentSourceContentAndKnownLanguages() {
        CatalogCandidate fresh = candidate("en", "spotify_native_model", "Line", 1000);
        assertFalse(CatalogAdapters.isPartialNativeDuplicate(
                candidate("unknown", "spotify_native_model:old", "Other line", 1000), fresh));
        assertFalse(CatalogAdapters.isPartialNativeDuplicate(
                candidate("unknown", "spotify_native_model:old", "Line", 2000), fresh));
        assertFalse(CatalogAdapters.isPartialNativeDuplicate(
                candidate("ja", "spotify_native_model:old", "Line", 1000), fresh));
        assertFalse(CatalogAdapters.isPartialNativeDuplicate(
                candidate("unknown", "spotify_native_db", "Line", 1000), fresh));
    }
}
