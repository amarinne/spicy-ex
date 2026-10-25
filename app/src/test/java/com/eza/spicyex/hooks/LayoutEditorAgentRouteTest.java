package com.eza.spicyex.hooks;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The agent's preview-tap route may only reach what a real tap can reach. The lyrics editor
 * stages the lyrics captures, and the card editor stages only its card: an element outside the
 * open stage is refused instead of selecting something no touch could.
 */
public class LayoutEditorAgentRouteTest {
    private static final String[] LYRICS_ELEMENTS = {
            "artwork", "track_text", "focus", "lyrics", "background", "skip", "follow", "top_bar"
    };

    @Test public void theLyricsEditorTapsItsOwnPreviewElements() {
        for (String name : LYRICS_ELEMENTS) {
            assertTrue(name, LyricsLayoutEditController.previewTapReachable(false, name));
        }
        assertFalse(LyricsLayoutEditController.previewTapReachable(false, "card"));
    }

    @Test public void theCardEditorTapsOnlyItsCard() {
        assertTrue(LyricsLayoutEditController.previewTapReachable(true, "card"));
        for (String name : LYRICS_ELEMENTS) {
            assertFalse(name, LyricsLayoutEditController.previewTapReachable(true, name));
        }
        assertFalse(LyricsLayoutEditController.previewTapReachable(true, ""));
    }
}
