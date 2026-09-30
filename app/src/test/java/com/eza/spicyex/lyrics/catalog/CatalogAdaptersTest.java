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

    @Test
    public void strictSingleSourceFailuresShareOneRetryPolicy() {
        // F9: these strict exits are now recorded by the provider itself, so automatic and
        // explicit requests persist the same outcome. An Apple 404 earns absence backoff
        // instead of staying NOT_CHECKED; transport and quality failures stay transient.
        assertEquals(ProviderStatus.NOT_FOUND,
                ProviderFailureClassifier.classify(SourceId.APPLE,
                        "Apple Music source unavailable: HTTP 404"));
        assertEquals(ProviderStatus.TRANSIENT_ERROR,
                ProviderFailureClassifier.classify(SourceId.APPLE,
                        "Apple Music source unavailable: rejected candidate"));
        assertEquals(ProviderStatus.TRANSIENT_ERROR,
                ProviderFailureClassifier.classify(SourceId.APPLE,
                        "Apple Music source unavailable: timeout"));
        assertEquals(ProviderStatus.TRANSIENT_ERROR,
                ProviderFailureClassifier.classify(SourceId.SPOTIFY_NATIVE,
                        "Spotify lyrics request failed"));
        assertEquals(ProviderStatus.TRANSIENT_ERROR,
                ProviderFailureClassifier.classify(SourceId.LRCLIB,
                        "strict LRCLIB; LRCLIB failed: timeout"));
    }

    private static CatalogPolicy policy(SourceId... enabled) {
        return new CatalogPolicy(Arrays.asList(enabled), false);
    }

    private static CatalogState rejectedApple() {
        CatalogState empty = CatalogState.empty("abc123");
        return new CatalogState(empty.trackId, empty.candidates, empty.providers,
                empty.selection,
                java.util.Collections.singleton(
                        CatalogState.rejectionKey(SourceId.APPLE, "abc123")),
                empty.known);
    }

    private static LyricsDocument appleDelivery() {
        LyricsDocument doc = document("apple_music_lenerd", "Syllable");
        doc.provider = "Apple Music";
        return doc;
    }

    @Test
    public void rejectedAppleItemNeverFallsBack() {
        // F4: providerSuccess refuses the rejected item, and the session must not show it
        // as an unsaved fallback either.
        assertTrue(CatalogAdapters.isRefusedFallback(
                policy(SourceId.APPLE, SourceId.LRCLIB), rejectedApple(), "abc123",
                appleDelivery()));
    }

    @Test
    public void disabledSourceNeverFallsBack() {
        assertTrue(CatalogAdapters.isRefusedFallback(
                policy(SourceId.LRCLIB), CatalogState.empty("abc123"), "abc123",
                appleDelivery()));
    }

    @Test
    public void enabledUnrejectedDeliveryKeepsStorageFailureFallback() {
        assertFalse(CatalogAdapters.isRefusedFallback(
                policy(SourceId.APPLE, SourceId.LRCLIB), CatalogState.empty("abc123"), "abc123",
                appleDelivery()));
    }

    @Test
    public void unknownProvenanceAndUnreadableInputsKeepLegacyFallback() {
        LyricsDocument unknown = document("unknown", "Line");
        unknown.provider = "";
        assertFalse(CatalogAdapters.isRefusedFallback(
                policy(SourceId.APPLE), CatalogState.empty("abc123"), "abc123", unknown));
        assertFalse(CatalogAdapters.isRefusedFallback(null, CatalogState.empty("abc123"),
                "abc123", appleDelivery()));
        assertFalse(CatalogAdapters.isRefusedFallback(
                policy(SourceId.APPLE), CatalogState.empty("abc123"), "abc123", null));
    }
}
