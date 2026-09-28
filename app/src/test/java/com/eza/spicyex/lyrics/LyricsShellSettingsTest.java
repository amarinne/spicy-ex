package com.eza.spicyex.lyrics;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class LyricsShellSettingsTest {
    @Test
    public void fullscreenControlTimeoutAcceptsNewRangeAndLegacyValues() {
        assertEquals(1, LyricsShellSettings.parseFullscreenControlsSeconds("1 second"));
        assertEquals(17, LyricsShellSettings.parseFullscreenControlsSeconds("17 seconds"));
        assertEquals(30, LyricsShellSettings.parseFullscreenControlsSeconds("30 seconds"));
        assertEquals(0, LyricsShellSettings.parseFullscreenControlsSeconds("Always"));
        assertEquals(0, LyricsShellSettings.parseFullscreenControlsSeconds("Always on"));
    }

    @Test
    public void fullscreenControlTimeoutSerializesCanonically() {
        assertEquals("1 second", LyricsShellSettings.fullscreenControlsValue(1));
        assertEquals("12 seconds", LyricsShellSettings.fullscreenControlsValue(12));
        assertEquals("Always", LyricsShellSettings.fullscreenControlsValue(0));
    }
}
