package com.eza.spicyex.hooks;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.eza.spicyex.settings.values.StoredValues;
import com.eza.spicyex.settings.values.TrackInfoArtSize;
import com.eza.spicyex.settings.values.TrackInfoPosition;
import com.eza.spicyex.settings.values.TrackInfoTextOverflow;

import org.junit.Test;

public class TrackInfoReadoutRulesTest {
    @Test
    public void sideModeNeedsWideLandscapeAndEnabledReadout() {
        assertTrue(TrackInfoReadoutController.sideModeEngaged(true, 2.0f, TrackInfoPosition.BOTTOM));
        assertTrue(TrackInfoReadoutController.sideModeEngaged(true, 1.2f, TrackInfoPosition.TOP));
        assertFalse(TrackInfoReadoutController.sideModeEngaged(true, 2.0f, TrackInfoPosition.OFF));
        assertFalse(TrackInfoReadoutController.sideModeEngaged(true, 1.19f, TrackInfoPosition.BOTTOM));
        assertFalse(TrackInfoReadoutController.sideModeEngaged(false, 2.0f, TrackInfoPosition.BOTTOM));
        // An unknown stored label reads as the default, which is no side mode.
        assertFalse(TrackInfoReadoutController.sideModeEngaged(true, 2.0f,
                StoredValues.fromStored(TrackInfoPosition.class, "Bogus")));
    }

    @Test
    public void artSizesMapToReadoutHeights() {
        assertArrayEquals(new int[]{72, 48},
                TrackInfoReadoutController.readoutArtSizes(TrackInfoArtSize.SMALL));
        assertArrayEquals(new int[]{96, 72},
                TrackInfoReadoutController.readoutArtSizes(TrackInfoArtSize.NORMAL));
        assertArrayEquals(new int[]{120, 96},
                TrackInfoReadoutController.readoutArtSizes(TrackInfoArtSize.LARGE));
        // An unknown stored label reads as the default size.
        assertArrayEquals(new int[]{96, 72}, TrackInfoReadoutController.readoutArtSizes(
                StoredValues.fromStored(TrackInfoArtSize.class, "Bogus")));
        assertArrayEquals(new int[]{96, 72}, TrackInfoReadoutController.readoutArtSizes(null));
    }

    @Test
    public void customArtSizeAppliesSameDpToEveryPlacement() {
        // Custom is driven by dragging the actual rendered frame's corner handle in the layout
        // editor, so every placement gets the exact size dragged to - no derived offset the way
        // the fixed presets use, or a Top-mode frame would end up smaller than what was dragged.
        assertArrayEquals(new int[]{96, 96},
                TrackInfoReadoutController.readoutArtSizes(TrackInfoArtSize.CUSTOM, 96));
        assertArrayEquals(new int[]{140, 140},
                TrackInfoReadoutController.readoutArtSizes(TrackInfoArtSize.CUSTOM, 140));
        // Clamped to a sane minimum.
        assertArrayEquals(new int[]{24, 24},
                TrackInfoReadoutController.readoutArtSizes(TrackInfoArtSize.CUSTOM, 4));
        // The stored range is wider for the two-column cover; readouts keep their own ceiling.
        assertArrayEquals(new int[]{160, 160},
                TrackInfoReadoutController.readoutArtSizes(TrackInfoArtSize.CUSTOM, 300));
        // Non-Custom values ignore the second argument and delegate to the fixed presets.
        assertArrayEquals(new int[]{72, 48},
                TrackInfoReadoutController.readoutArtSizes(TrackInfoArtSize.SMALL, 999));
    }

    @Test
    public void overflowModesNormalizeWithWrapDefault() {
        assertEquals(TrackInfoTextOverflow.CLIP,
                TrackInfoReadoutController.normalizeOverflow(TrackInfoTextOverflow.CLIP));
        assertEquals(TrackInfoTextOverflow.WRAP,
                TrackInfoReadoutController.normalizeOverflow(TrackInfoTextOverflow.WRAP));
        assertEquals(TrackInfoTextOverflow.SCROLL,
                TrackInfoReadoutController.normalizeOverflow(TrackInfoTextOverflow.SCROLL));
        assertEquals(TrackInfoTextOverflow.WRAP,
                TrackInfoReadoutController.normalizeOverflow(
                        StoredValues.fromStored(TrackInfoTextOverflow.class, "Bogus")));
        assertEquals(TrackInfoTextOverflow.WRAP,
                TrackInfoReadoutController.normalizeOverflow(null));
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
