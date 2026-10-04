package com.eza.spicyex.hooks;

import com.eza.spicyex.lyrics.AppliedLine;
import com.eza.spicyex.lyrics.LyricsDocument;

import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.*;

public class SpicyOrgBridgeRestrictionTest {
    @Test public void restrictedDocumentCannotLeaveViaSerializedBridge() throws Exception {
        LyricsDocument doc = new LyricsDocument();
        doc.fetchSource = "spicy_org_cache";
        doc.provider = "Apple Music";
        AppliedLine line = new AppliedLine();
        line.text = "restricted lyric";
        doc.appliedLines.add(line);
        try {
            SpicyLyricBridgeDocumentSerializer.serialize(doc, "test", 1, "track");
            fail("Restricted source must never be serialized to an external consumer");
        } catch (IOException expected) {
            assertEquals("provider does not permit lyric redistribution", expected.getMessage());
        }
        doc.fetchSource = "native";
        assertTrue(SpicyLyricBridgeDocumentSerializer.serialize(doc, "test", 1, "track").length > 0);
    }
}
