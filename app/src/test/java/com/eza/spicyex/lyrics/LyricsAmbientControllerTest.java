package com.eza.spicyex.lyrics;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class LyricsAmbientControllerTest {
    @Test
    public void sampleSizeKeepsLongestEdgeNearTarget() {
        assertEquals(4, LyricsAmbientController.calculateInSampleSize(2048, 2048, 384));
        assertEquals(2, LyricsAmbientController.calculateInSampleSize(1200, 600, 384));
        assertEquals(1, LyricsAmbientController.calculateInSampleSize(640, 360, 384));
    }

    @Test
    public void sampleSizeHandlesTinyAndInvalidBounds() {
        assertEquals(1, LyricsAmbientController.calculateInSampleSize(96, 96, 384));
        assertEquals(1, LyricsAmbientController.calculateInSampleSize(-1, -1, 384));
        assertEquals(1, LyricsAmbientController.calculateInSampleSize(800, 400, 0));
    }
}
