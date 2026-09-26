package com.eza.spicyex.lyrics.catalog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.lyrics.LyricsLine;
import com.eza.spicyex.lyrics.session.CanonicalSourceCodec;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class CatalogCodecTest {
    @Test
    public void transliterationSlotRoundTripsPerLineText() {
        String encoded = CatalogCodec.encodeProviderTransliteration(
                Arrays.asList("かみ", "", "せかい"));
        assertEquals(Arrays.asList("かみ", "", "せかい"),
                CatalogCodec.decodeProviderTransliteration(encoded));
    }

    @Test
    public void corruptTransliterationPayloadDecodesToEmpty() {
        assertTrue(CatalogCodec.decodeProviderTransliteration(null).isEmpty());
        assertTrue(CatalogCodec.decodeProviderTransliteration("").isEmpty());
        assertTrue(CatalogCodec.decodeProviderTransliteration("not json").isEmpty());
        assertTrue(CatalogCodec.decodeProviderTransliteration("{\"a\":1}").isEmpty());
    }

    @Test
    public void normalizedDocumentKeepsProviderTranslationButDropsGeneratedText() {
        LyricsDocument doc = new LyricsDocument();
        doc.trackId = "spotify:track:abc";
        doc.fetchSource = "apple_music";
        doc.type = "Line";
        LyricsLine line = new LyricsLine();
        line.text = "hello";
        line.startMs = 1000;
        line.endMs = 2000;
        line.providerTranslatedText = "こんにちは";
        line.providerTranslationLanguage = "ja";
        line.romanizedText = "generated romaji that must not persist";
        line.translatedText = "generated translation that must not persist";
        doc.lines.add(line);

        String encoded = CanonicalSourceCodec.encode(doc, 1, "digest-a", 123L, "");
        CanonicalSourceCodec.Record record = CanonicalSourceCodec.decode(encoded);

        assertNotNull(record);
        assertEquals("こんにちは", record.document.lines.get(0).providerTranslatedText);
        assertEquals("ja", record.document.lines.get(0).providerTranslationLanguage);
        assertEquals("", record.document.lines.get(0).romanizedText);
        assertEquals("", record.document.lines.get(0).translatedText);
    }

    @Test
    public void rawPayloadCompressionRoundTrips() {
        String payload = "{\"trackName\":\"Song\",\"syncedLyrics\":\"[00:01.00] hello\\n\"}";
        assertEquals(payload, CatalogCodec.inflate(CatalogCodec.deflate(payload)));
        assertTrue(CatalogCodec.inflate(CatalogCodec.deflate("")).isEmpty());
        assertTrue(CatalogCodec.inflate(null).isEmpty());
        assertTrue(CatalogCodec.inflate(new byte[]{1, 2, 3}).isEmpty());
    }

    @Test
    public void emptyTransliterationBaselineMatchesLineCount() {
        assertEquals(Collections.emptyList(), CatalogCodec.decodeProviderTransliteration(
                CatalogCandidate.emptyTransliteration(0)));
        assertEquals(Arrays.asList("", ""),
                CatalogCodec.decodeProviderTransliteration(CatalogCandidate.emptyTransliteration(2)));
    }

    @Test
    public void stableCandidateIdDistinguishesContentVersions() {
        String first = CatalogCandidate.stableId("track-1", CatalogSource.SourceId.APPLE,
                "item-1", "aaa");
        String refreshed = CatalogCandidate.stableId("track-1", CatalogSource.SourceId.APPLE,
                "item-1", "bbb");
        String otherItem = CatalogCandidate.stableId("track-1", CatalogSource.SourceId.APPLE,
                "item-2", "aaa");
        assertTrue(!first.equals(refreshed));
        assertTrue(!first.equals(otherItem));
        assertTrue(!first.equals(CatalogCandidate.stableId("track-2",
                CatalogSource.SourceId.APPLE, "item-1", "aaa")));
        assertNull(CatalogLegacyImport.candidateFromRecord(null, "spotify:track:abc", 0L));
    }
}
