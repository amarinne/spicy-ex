package com.eza.spicyex.lyrics;

import static org.junit.Assert.assertEquals;

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
}
