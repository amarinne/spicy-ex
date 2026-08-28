package com.eza.spicyex.lyrics;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LyricsRenderModeTest {
    @Test
    public void onlyTrustedTimingTypesCanUseKaraokeDimming() {
        assertFalse(LyricsRenderMode.isStatic(document("Line")));
        assertFalse(LyricsRenderMode.isStatic(document("Syllable")));
        assertFalse(LyricsRenderMode.isStatic(document("Word")));
        assertTrue(LyricsRenderMode.isStatic(document("Static")));
        assertTrue(LyricsRenderMode.isStatic(document("Unknown")));
        assertTrue(LyricsRenderMode.isStatic(document("")));
        assertTrue(LyricsRenderMode.isStatic(null));
    }

    @Test
    public void oneRowProjectionPreservesSourceTimingTrust() {
        LyricsDocument projection = document("Unknown");

        LyricsRenderMode.copyTimingType(document("Syllable"), projection);

        assertFalse(LyricsRenderMode.isStatic(projection));
    }

    private static LyricsDocument document(String type) {
        LyricsDocument document = new LyricsDocument();
        document.type = type;
        return document;
    }
}
