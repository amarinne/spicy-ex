package com.eza.spicyex.lyrics.catalog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.lyrics.LyricsLine;
import com.eza.spicyex.lyrics.catalog.CatalogSource.SelectionMode;
import com.eza.spicyex.lyrics.catalog.CatalogSource.SourceId;
import com.eza.spicyex.lyrics.session.CanonicalSourceCodec;

import org.junit.Test;

public class CatalogLegacyImportTest {
    @Test
    public void retiredSpicyOverrideBecomesManualApple() {
        CatalogLegacyImport.LegacyPick pick = CatalogLegacyImport.pickForLegacyOverride("spicy");
        assertEquals(SelectionMode.MANUAL, pick.mode);
        assertEquals(SourceId.APPLE, pick.sourceId);
    }

    @Test
    public void directMusixmatchOverrideBecomesAuto() {
        CatalogLegacyImport.LegacyPick pick = CatalogLegacyImport.pickForLegacyOverride("musixmatch");
        assertEquals(SelectionMode.AUTO, pick.mode);
        assertNull(pick.sourceId);
    }

    @Test
    public void autoAndUnknownOverridesBecomeAuto() {
        assertEquals(SelectionMode.AUTO, CatalogLegacyImport.pickForLegacyOverride(null).mode);
        assertEquals(SelectionMode.AUTO, CatalogLegacyImport.pickForLegacyOverride("auto").mode);
        assertEquals(SelectionMode.AUTO, CatalogLegacyImport.pickForLegacyOverride("bogus").mode);
    }

    @Test
    public void liveSourceOverridesStayManual() {
        CatalogLegacyImport.LegacyPick pick = CatalogLegacyImport.pickForLegacyOverride("lrclib");
        assertEquals(SelectionMode.MANUAL, pick.mode);
        assertEquals(SourceId.LRCLIB, pick.sourceId);
    }

    @Test
    public void legacyWinnerBecomesOneCandidate() {
        LyricsDocument doc = new LyricsDocument();
        doc.trackId = "spotify:track:abc123";
        doc.fetchSource = "apple_music";
        doc.type = "Line";
        doc.songWriters = "Writer";
        LyricsLine line = new LyricsLine();
        line.text = "hello";
        line.startMs = 1000;
        line.endMs = 2000;
        line.providerTranslatedText = "こんにちは";
        doc.lines.add(line);
        CanonicalSourceCodec.Record record = CanonicalSourceCodec.decode(
                CanonicalSourceCodec.encode(doc, 2, "digest-a", 456L, ""));

        CatalogCandidate candidate =
                CatalogLegacyImport.candidateFromRecord(record, "spotify:track:abc123", 789L);

        assertNotNull(candidate);
        assertEquals("abc123", candidate.trackId);
        assertEquals(SourceId.APPLE, candidate.sourceId);
        assertEquals("legacy-winner", candidate.providerItemId);
        assertEquals("digest-a", candidate.canonicalDigest);
        assertTrue(candidate.complete);
        assertTrue(candidate.timingHealthy);
        assertTrue(candidate.hasProviderTranslation);
        assertTrue(candidate.hasCredits);
        assertTrue(candidate.providerTransliterationLines().size() == 1);
        assertTrue(!candidate.normalizedDocument.isEmpty());
    }

    @Test
    public void legacyWinnerWithoutIdentityIsSkipped() {
        LyricsDocument doc = new LyricsDocument();
        doc.lines.add(new LyricsLine());
        CanonicalSourceCodec.Record record = CanonicalSourceCodec.decode(
                CanonicalSourceCodec.encode(doc, 1, "", 0L, ""));
        assertNull(CatalogLegacyImport.candidateFromRecord(record, "spotify:episode:xyz", 0L));
        assertNull(CatalogLegacyImport.candidateFromRecord(record, "", 0L));
    }
}
