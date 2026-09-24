package com.eza.spicyex.hooks;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TrackInfoReadoutRulesTest {
    @Test
    public void sideModeNeedsWideLandscapeAndEnabledReadout() {
        assertTrue(TrackInfoReadoutController.sideModeEngaged(true, 2.0f, "Bottom"));
        assertTrue(TrackInfoReadoutController.sideModeEngaged(true, 1.2f, "Top"));
        assertFalse(TrackInfoReadoutController.sideModeEngaged(true, 2.0f, "Off"));
        assertFalse(TrackInfoReadoutController.sideModeEngaged(true, 1.19f, "Bottom"));
        assertFalse(TrackInfoReadoutController.sideModeEngaged(false, 2.0f, "Bottom"));
        assertFalse(TrackInfoReadoutController.sideModeEngaged(true, 2.0f, "Bogus"));
    }

    @Test
    public void artSizesMapToReadoutHeights() {
        assertArrayEquals(new int[]{72, 48}, TrackInfoReadoutController.readoutArtSizes("Small"));
        assertArrayEquals(new int[]{96, 72}, TrackInfoReadoutController.readoutArtSizes("Normal"));
        assertArrayEquals(new int[]{120, 96}, TrackInfoReadoutController.readoutArtSizes("Large"));
        assertArrayEquals(new int[]{96, 72}, TrackInfoReadoutController.readoutArtSizes("Bogus"));
        assertArrayEquals(new int[]{96, 72}, TrackInfoReadoutController.readoutArtSizes(null));
    }

    @Test
    public void customArtSizeAppliesSameDpToEveryPlacement() {
        // Custom is driven by dragging the actual rendered frame's corner handle in the layout
        // editor, so every placement gets the exact size dragged to - no derived offset the way
        // the fixed presets use, or a Top-mode frame would end up smaller than what was dragged.
        assertArrayEquals(new int[]{96, 96},
                TrackInfoReadoutController.readoutArtSizes("Custom", 96));
        assertArrayEquals(new int[]{140, 140},
                TrackInfoReadoutController.readoutArtSizes("Custom", 140));
        // Clamped to a sane minimum.
        assertArrayEquals(new int[]{24, 24},
                TrackInfoReadoutController.readoutArtSizes("Custom", 4));
        // Non-Custom values ignore the second argument and delegate to the fixed presets.
        assertArrayEquals(new int[]{72, 48},
                TrackInfoReadoutController.readoutArtSizes("Small", 999));
    }

    @Test
    public void overflowModesNormalizeWithWrapDefault() {
        assertTrue("Clip".equals(TrackInfoReadoutController.normalizeOverflow("Clip")));
        assertTrue("Wrap".equals(TrackInfoReadoutController.normalizeOverflow("Wrap")));
        assertTrue("Scroll".equals(TrackInfoReadoutController.normalizeOverflow("Scroll")));
        assertTrue("Wrap".equals(TrackInfoReadoutController.normalizeOverflow("Bogus")));
        assertTrue("Wrap".equals(TrackInfoReadoutController.normalizeOverflow(null)));
    }

    @Test
    public void narrowTopDockNeedsFitAndAspect() {
        // 360-wide portrait: 20*2 + 72 art + 120 text + (3*44 + 2*8) cluster = 380 > 360.
        assertTrue(TrackInfoReadoutController.narrowTopDock(360, 800, 72, 120, 148, 20));
        // 1000x600 landscape: band fits and aspect is wide.
        assertFalse(TrackInfoReadoutController.narrowTopDock(1000, 600, 54, 120, 148, 72));
        // Degenerate sizes never claim narrow.
        assertFalse(TrackInfoReadoutController.narrowTopDock(0, 800, 72, 120, 148, 20));
    }
}
