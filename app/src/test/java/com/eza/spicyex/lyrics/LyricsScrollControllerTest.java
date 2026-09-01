package com.eza.spicyex.lyrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LyricsScrollControllerTest {
    @Test
    public void contentCenterYSubtractsScrollPadding() {
        assertEquals(500, LyricsScrollController.contentCenterY(900, 1200, 1000));
    }

    @Test
    public void contentCenterYClampsNegativePadding() {
        assertEquals(1500, LyricsScrollController.contentCenterY(900, 1200, -24));
    }

    @Test
    public void firstActiveRowUsesInstantPlacementAfterDocumentReset() {
        assertTrue(LyricsScrollController.shouldScrollInstantly(false, -1));
    }

    @Test
    public void laterActiveRowsKeepRequestedScrollMode() {
        assertFalse(LyricsScrollController.shouldScrollInstantly(false, 0));
        assertTrue(LyricsScrollController.shouldScrollInstantly(true, 0));
    }

}
