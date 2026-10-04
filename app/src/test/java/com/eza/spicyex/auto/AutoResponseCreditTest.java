package com.eza.spicyex.auto;

import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.lyrics.providers.SpicyOrgPolicy;
import org.junit.Test;
import static org.junit.Assert.*;

public class AutoResponseCreditTest {
    @Test public void orgDocumentsProjectUntilTheirOriginalRetentionDeadline() {
        LyricsDocument org = new LyricsDocument();
        org.fetchSource = "spicy_org_cache";
        org.spicyOrgFetchedAtMs = 1000;
        assertTrue(AutoResponseCredit.documentAllowed(org, 1000 + SpicyOrgPolicy.RETENTION_MS - 1));
        assertFalse(AutoResponseCredit.documentAllowed(org, 1000 + SpicyOrgPolicy.RETENTION_MS));
        assertFalse(AutoResponseCredit.documentAllowed(org, 999));
        assertFalse(AutoResponseCredit.documentAllowed(null, 1000));
        org.fetchSource = "native";
        assertTrue(AutoResponseCredit.documentAllowed(org, Long.MAX_VALUE));
    }

    @Test public void projectedPacketKeepsTheAcquisitionClock() {
        assertTrue(AutoResponseCredit.packetAllowed(true, 1000, 1000 + SpicyOrgPolicy.RETENTION_MS - 1));
        assertFalse(AutoResponseCredit.packetAllowed(true, 1000, 1000 + SpicyOrgPolicy.RETENTION_MS));
        assertFalse(AutoResponseCredit.packetAllowed(true, 0, 1000));
        assertFalse(AutoResponseCredit.packetAllowed(true, 1001, 1000));
        assertTrue(AutoResponseCredit.packetAllowed(false, 0, 1000));
    }

    @Test public void creditReplacesOutroAtExactBoundaryAndSeekingBackRestoresLyrics() {
        assertFalse(AutoResponseCredit.outro(true, 8000, 8699, true));
        assertTrue(AutoResponseCredit.outro(true, 8000, 8700, true));
        assertFalse(AutoLyricPresentation.nativeArtwork(true, true, 1000, 8000, 8700, false, true));
        assertTrue(AutoLyricPresentation.nativeArtwork(true, true, 1000, 8000, 8700, false, false));
        assertFalse(AutoResponseCredit.outro(true, 8000, 2000, true));
        assertTrue(AutoLyricPresentation.nativeArtwork(true, true, 4000, 8000, 3000, false, true));
        assertFalse(AutoResponseCredit.outro(false, 8000, 8700, true));
        assertFalse(AutoResponseCredit.outro(true, 0, 8700, true));
        assertFalse(AutoResponseCredit.outro(true, 8000, 8700, false));
    }
}
