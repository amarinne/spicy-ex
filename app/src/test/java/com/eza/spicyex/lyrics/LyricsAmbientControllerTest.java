package com.eza.spicyex.lyrics;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class LyricsAmbientControllerTest {
    @Test
    public void darkeningAppliesToAnyBackgroundStyle() {
        assertEquals(0.65f, LyricsAmbientController.backgroundBrightness(35), 0.0001f);
        assertEquals(1f, LyricsAmbientController.backgroundBrightness(0), 0.0001f);
        assertEquals(0f, LyricsAmbientController.backgroundBrightness(150), 0.0001f);
    }
}
