package com.eza.spicyex.lyrics.catalog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.eza.spicyex.SpotifyTrack;
import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.lyrics.LyricsLine;
import com.eza.spicyex.lyrics.catalog.CatalogSource.MatchMethod;
import com.eza.spicyex.lyrics.catalog.CatalogSource.ProviderStatus;
import com.eza.spicyex.lyrics.catalog.CatalogSource.SourceId;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class CatalogAdaptersTest {
    private static final SpotifyTrack TRACK = new SpotifyTrack(
            "Song", "Artist", "Album", "spotify:track:abc123", 0, "", 0, null, 180000, false);

    private static LyricsDocument document(String fetchSource, String type) {
        LyricsDocument doc = new LyricsDocument();
        doc.trackId = "abc123";
        doc.fetchSource = fetchSource;
        doc.type = type;
        doc.songWriters = "Writer";
        LyricsLine line = new LyricsLine();
        line.text = "hello";
        line.startMs = 1000;
        line.endMs = 2000;
        line.providerTranslatedText = "こんにちは";
        line.oppositeAligned = true;
        doc.lines.add(line);
        LyricsLine second = new LyricsLine();
        second.text = "world";
        second.startMs = 2000;
        second.endMs = 3000;
        doc.lines.add(second);
        return doc;
    }

    @Test
    public void fallbackOrderPutsAmllBeforeLrclib() {
        List<SourceId> order = CatalogAdapters.fallbackOrder();
        assertEquals(Arrays.asList(SourceId.AMLL, SourceId.LRCLIB), order);
    }

    @Test
    public void exactIdQueriesMapToExactMappingForDirectSources() {
        assertEquals(MatchMethod.EXACT_SPOTIFY_ID,
                CatalogAdapters.matchMethodForQuery(SourceId.AMLL, true));
        assertEquals(MatchMethod.STRONG_SEARCH,
                CatalogAdapters.matchMethodForQuery(SourceId.AMLL, false));
        assertEquals(MatchMethod.STRONG_SEARCH,
                CatalogAdapters.matchMethodForQuery(SourceId.LRCLIB, true));
    }

    @Test
    public void deliveredLrclibDocumentBuildsAStorableCandidate() {
        CatalogCandidate candidate = CatalogAdapters.buildCandidate(SourceId.LRCLIB, TRACK,
                document("lrclib", "Line"), MatchMethod.STRONG_SEARCH, "",
                "[{\"trackName\":\"Song\"}]", CatalogAdapters.LRCLIB_ADAPTER_REVISION, 1000L);

        assertNotNull(candidate);
        assertEquals("abc123", candidate.trackId);
        assertEquals(SourceId.LRCLIB, candidate.sourceId);
        assertTrue(candidate.complete);
        assertTrue(candidate.timingHealthy);
        assertTrue(candidate.hasProviderTranslation);
        assertTrue(candidate.hasDuet);
        assertTrue(candidate.hasCredits);
        assertEquals(2, candidate.providerTransliterationLines().size());
        assertEquals("[{\"trackName\":\"Song\"}]",
                CatalogCodec.inflate(candidate.rawPayload));
        assertTrue(!candidate.normalizedDocument.isEmpty());
        assertTrue(!candidate.canonicalDigest.isEmpty());
    }

    @Test
    public void malformedDocumentsAreRefusedNeverStored() {
        assertNull(CatalogAdapters.buildCandidate(SourceId.LRCLIB, TRACK, null,
                MatchMethod.STRONG_SEARCH, "", "", 1, 0L));
        assertNull(CatalogAdapters.buildCandidate(SourceId.LRCLIB, TRACK, new LyricsDocument(),
                MatchMethod.STRONG_SEARCH, "", "", 1, 0L));
        assertNull(CatalogAdapters.buildCandidate(SourceId.LRCLIB,
                new SpotifyTrack("S", "A", "B", "spotify:episode:xyz", 0, "", 0, null, 0, false),
                document("lrclib", "Line"), MatchMethod.STRONG_SEARCH, "", "", 1, 0L));
        assertNull(CatalogAdapters.buildCandidate(null, TRACK, document("lrclib", "Line"),
                MatchMethod.STRONG_SEARCH, "", "", 1, 0L));
    }

    @Test
    public void emptyResultsAreNotFoundTransientsAreTransient() {
        assertEquals(ProviderStatus.NOT_FOUND,
                ProviderFailureClassifier.classify(SourceId.LRCLIB, "chain; LRCLIB empty"));
        assertEquals(ProviderStatus.NOT_FOUND,
                ProviderFailureClassifier.classify(SourceId.LRCLIB, "LRCLIB HTTP 404"));
        assertEquals(ProviderStatus.NOT_FOUND,
                ProviderFailureClassifier.classify(SourceId.AMLL,
                        "AMLL source unavailable: no match"));
        assertEquals(ProviderStatus.NOT_FOUND,
                ProviderFailureClassifier.classify(SourceId.LRCLIB, "no LRCLIB result"));
        assertEquals(ProviderStatus.TRANSIENT_ERROR,
                ProviderFailureClassifier.classify(SourceId.LRCLIB, "LRCLIB failed: timeout"));
        assertEquals(ProviderStatus.TRANSIENT_ERROR,
                ProviderFailureClassifier.classify(SourceId.LRCLIB, "LRCLIB HTTP 503"));
        assertEquals(ProviderStatus.TRANSIENT_ERROR,
                ProviderFailureClassifier.classify(SourceId.LRCLIB,
                        "LRCLIB parse failed: unexpected"));
        assertEquals(ProviderStatus.TRANSIENT_ERROR,
                ProviderFailureClassifier.classify(SourceId.AMLL, null));
        assertEquals(ProviderStatus.TRANSIENT_ERROR,
                ProviderFailureClassifier.classify(SourceId.AMLL, ""));
        assertEquals(ProviderStatus.DISABLED,
                ProviderFailureClassifier.classify(SourceId.QQ, "source disabled"));
    }
}
