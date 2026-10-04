package com.eza.spicyex.lyrics.providers;

import com.eza.spicyex.SpotifyTrack;
import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.lyrics.catalog.*;
import com.eza.spicyex.lyrics.session.CanonicalSourceCodec;
import org.junit.Test;
import okhttp3.Request;
import static org.junit.Assert.*;

public class SpicyOrgProtocolTest {
    private static final String ID = "1QV6tiMFM6fSOKOGLMHYYg";
    private final SpotifyTrack track = new SpotifyTrack("Song", "Artist", "Album",
            "spotify:track:" + ID, 0, "", 0, null, 180000, false);
    private final LyricsParser parser = new LyricsParser(null);

    private String envelope(String content) {
        return "{\"Status\":200,\"Type\":\"object\",\"Body\":{\"id\":\"" + ID
                + "\"," + content + "}}";
    }

    @Test public void nativeRequestUsesOnlyPersonalClientKeyAndNoOrigin() {
        Request request = SpicyOrgProtocol.request(ID, "sl_pk_test");
        assertEquals("GET", request.method());
        assertEquals("https://api.spicylyrics.org/v1/lyrics/" + ID, request.url().toString());
        assertNull(request.header("Origin"));
        assertEquals("Bearer sl_pk_test", request.header("Authorization"));
        assertThrows(IllegalArgumentException.class,
                () -> SpicyOrgProtocol.request(ID, "sl_sk_server"));
        assertThrows(IllegalArgumentException.class,
                () -> SpicyOrgProtocol.request("../query", "sl_pk_test"));
    }

    @Test public void staticCreditAndMarksSurviveCopyAndCacheReplay() {
        String raw = envelope("\"source\":\"spicy_lyrics\",\"Type\":\"Static\","
                + "\"UploadAttribution\":{\"Uploader\":{\"username\":\"uploader\","
                + "\"url\":\"https://spicylyrics.org/uid/42\"}},"
                + "\"Lines\":[{\"Text\":\"hel\\u200blo\"}]");
        LyricsDocument doc = parser.parseSpicyOrgLyrics(null, track, raw);
        assertEquals("hel\u200blo", doc.lines.get(0).text);
        assertEquals("Spicy Lyrics", doc.provider);
        assertEquals("uploader", doc.spicyOrgUploader);
        assertEquals("", doc.spicyOrgMaker);
        assertEquals(raw, doc.spicyOrgRawPayload);
        LyricsDocument copied = LyricsDocument.copyOf(doc);
        LyricsDocument restored = CanonicalSourceCodec.decode(CanonicalSourceCodec.encode(
                copied, 1, "digest", System.currentTimeMillis())).document;
        assertEquals(doc.spicyOrgUploaderUrl, restored.spicyOrgUploaderUrl);
        assertEquals(doc.lines.get(0).text, restored.lines.get(0).text);
        assertEquals(raw, restored.spicyOrgRawPayload);
        LyricsDocument otherProvider = LyricsDocument.copyOf(doc);
        otherProvider.fetchSource = "apple_music";
        assertNotEquals(com.eza.spicyex.lyrics.session.CanonicalBase.fromDocument(doc.trackId, doc).digest,
                com.eza.spicyex.lyrics.session.CanonicalBase.fromDocument(doc.trackId, otherProvider).digest);
    }

    @Test public void syllableContinuationAppliesToTheNextSyllableAndSecondsBecomeMillis() {
        String raw = envelope("\"source\":\"apple_music\",\"Type\":\"Syllable\","
                + "\"Content\":[{\"Type\":\"Vocal\",\"Lead\":{\"StartTime\":1,\"EndTime\":3,"
                + "\"Syllables\":[{\"Text\":\"Hel\",\"StartTime\":1,\"EndTime\":1.5,\"IsPartOfWord\":true},"
                + "{\"Text\":\"lo\",\"StartTime\":1.5,\"EndTime\":2,\"IsPartOfWord\":false},"
                + "{\"Text\":\"world\",\"StartTime\":2,\"EndTime\":3,\"IsPartOfWord\":false}]}}]");
        LyricsDocument doc = parser.parseSpicyOrgLyrics(null, track, raw);
        assertEquals("Hello world", doc.lines.get(0).text);
        assertEquals(1500, doc.lines.get(0).syllables.get(1).startMs);
        assertEquals("Apple Music", doc.provider);
        assertTrue(SpicyOrgPolicy.isRestricted(doc));
    }

    @Test public void unknownSourceDoesNotBecomeAppleAndMissingCreditIsRefused() {
        LyricsDocument unknown = parser.parseSpicyOrgLyrics(null, track,
                envelope("\"Type\":\"Static\",\"Lines\":[{\"Text\":\"hello\"}]"));
        assertEquals("Unknown", unknown.provider);
        assertThrows(IllegalArgumentException.class, () -> parser.parseSpicyOrgLyrics(null, track,
                envelope("\"source\":\"spicy_lyrics\",\"Type\":\"Static\",\"Lines\":[{\"Text\":\"hello\"}]")));
        assertThrows(IllegalArgumentException.class,
                () -> SpicyOrgProtocol.body(envelope("\"Type\":\"Static\""), "another"));
    }

    @Test public void cacheExpiresAtThirtyDaysIncludingManualCandidateAndFutureTimestamp() {
        LyricsDocument doc = parser.parseSpicyOrgLyrics(null, track,
                envelope("\"source\":\"apple_music\",\"Type\":\"Static\",\"Lines\":[{\"Text\":\"hello\"}]"));
        long now = System.currentTimeMillis();
        assertFalse(SpicyOrgPolicy.expiredAt(now - SpicyOrgPolicy.RETENTION_MS + 1, now));
        assertTrue(SpicyOrgPolicy.expiredAt(now - SpicyOrgPolicy.RETENTION_MS, now));
        assertTrue(SpicyOrgPolicy.expiredAt(now + 1, now));
        doc.spicyOrgFetchedAtMs = now - SpicyOrgPolicy.RETENTION_MS;
        assertNull(CanonicalSourceCodec.decode(CanonicalSourceCodec.encode(doc, 1, "digest", now)));
        CatalogCandidate candidate = CatalogAdapters.buildCandidate(CatalogSource.SourceId.SPICY_ORG,
                track, doc, CatalogSource.MatchMethod.EXACT_SPOTIFY_ID, ID, "", 1,
                now - SpicyOrgPolicy.RETENTION_MS);
        assertNotNull(candidate);
        assertFalse(candidate.hasValidProviderTiming());
    }

    @Test public void quotaHeadersAreRelativeAndOnly404IsDurableAbsence() {
        assertEquals(50, SpicyOrgProtocol.seconds("50"));
        assertEquals(0, SpicyOrgProtocol.seconds("invalid"));
        assertEquals(CatalogSource.ProviderStatus.NOT_FOUND, ProviderFailureClassifier.classify(
                CatalogSource.SourceId.SPICY_ORG, SpicyOrgProtocol.error(404)));
        for (int status : new int[]{401, 403, 429, 500, 502, 503}) {
            assertEquals(CatalogSource.ProviderStatus.TRANSIENT_ERROR,
                    ProviderFailureClassifier.classify(CatalogSource.SourceId.SPICY_ORG,
                            SpicyOrgProtocol.error(status)));
        }
        assertEquals(CatalogSource.SourceId.SPICY_ORG,
                CatalogSource.inferSourceId("spicy_org", "Apple Music"));
        assertEquals(CatalogSource.SourceId.APPLE,
                CatalogSource.inferSourceId("spicy", "Spicy Lyrics"));
    }
}
