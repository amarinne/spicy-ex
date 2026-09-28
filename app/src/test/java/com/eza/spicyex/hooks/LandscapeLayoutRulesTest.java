package com.eza.spicyex.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LandscapeLayoutRulesTest {
    @Test
    public void twoColumnNeedsAdaptiveWideLandscape() {
        // Landscape phones.
        assertTrue(NativeSpicyShellViewImpl.twoColumnEngaged(800f, 400f, true));
        assertTrue(NativeSpicyShellViewImpl.twoColumnEngaged(480f, 400f, true));
        assertFalse(NativeSpicyShellViewImpl.twoColumnEngaged(476f, 400f, true));
        // Portrait phones stay stacked.
        assertFalse(NativeSpicyShellViewImpl.twoColumnEngaged(400f, 860f, true));
        // Unfolded foldables are near-square and large: two columns either way round.
        assertTrue(NativeSpicyShellViewImpl.twoColumnEngaged(840f, 900f, true));
        assertTrue(NativeSpicyShellViewImpl.twoColumnEngaged(900f, 840f, true));
        // Tall tablets in portrait stay stacked.
        assertFalse(NativeSpicyShellViewImpl.twoColumnEngaged(800f, 1280f, true));
        // The setting still turns it off.
        assertFalse(NativeSpicyShellViewImpl.twoColumnEngaged(800f, 400f, false));
    }

    @Test
    public void columnCoverFollowsCustomArtSizeWithinItsColumn() {
        // Presets are readout sizes; the cover keeps filling its column.
        assertEquals(700, TrackInfoReadoutController.columnCoverSidePx(700, "Normal", 300));
        assertEquals(700, TrackInfoReadoutController.columnCoverSidePx(700, "Large", 300));
        // Custom caps the cover, but never past the room the column has.
        assertEquals(300, TrackInfoReadoutController.columnCoverSidePx(700, "Custom", 300));
        assertEquals(700, TrackInfoReadoutController.columnCoverSidePx(700, "Custom", 1200));
        assertEquals(700, TrackInfoReadoutController.columnCoverSidePx(700, "Custom", 0));
    }

    @Test
    public void shrunkCoverKeepsReadableSongInfoWidth() {
        // A full cover and its text share the cover's width.
        assertEquals(700, TrackInfoReadoutController.columnBlockWidthPx(700, 700, 600));
        // A shrunk cover leaves the text its minimum width.
        assertEquals(600, TrackInfoReadoutController.columnBlockWidthPx(200, 700, 600));
        // A short column whose whole fit is below the minimum does not widen past it.
        assertEquals(450, TrackInfoReadoutController.columnBlockWidthPx(300, 450, 600));
        // Before the first measure there is no fit yet: the cover's own width.
        assertEquals(300, TrackInfoReadoutController.columnBlockWidthPx(300, -1, 600));
    }

    @Test
    public void landscapeHonoursExplicitFocusAndCentresAuto() {
        float d = 0.0001f;
        // Auto: centred in landscape even with line-slide on; raised in portrait with it on.
        assertEquals(0.5f, NativeSpicyShellViewImpl.focusAnchorFraction("Auto", 50, true, true), d);
        assertEquals(0.28f, NativeSpicyShellViewImpl.focusAnchorFraction("Auto", 50, false, true), d);
        assertEquals(0.5f, NativeSpicyShellViewImpl.focusAnchorFraction("Auto", 50, false, false), d);
        // Explicit choices hold in landscape too.
        assertEquals(0.28f, NativeSpicyShellViewImpl.focusAnchorFraction("Top", 50, true, false), d);
        assertEquals(0.72f, NativeSpicyShellViewImpl.focusAnchorFraction("Bottom", 50, true, false), d);
        assertEquals(0.35f, NativeSpicyShellViewImpl.focusAnchorFraction("Custom", 35, true, false), d);
        assertEquals(1f, NativeSpicyShellViewImpl.focusAnchorFraction("Custom", 140, true, false), d);
    }

    @Test
    public void columnTrackTextScaleMirrorsReadoutSizeModes() {
        // "Normal" is 1.0, the ratio the block's own 20/15/14sp bases are written at.
        assertEquals(1f, scale("Normal", 100, 96f), 0.0001f);
        // The readout's title sizes (13/18/22sp off its 15sp "Normal") as a ratio.
        assertEquals(13f / 15f, scale("Small", 100, 96f), 0.0001f);
        assertEquals(1.2f, scale("Large", 100, 96f), 0.0001f);
        assertEquals(22f / 15f, scale("XLarge", 100, 96f), 0.0001f);
        // Unknown or missing values stay at Normal, like the readout's own fallback.
        assertEquals(1f, scale("Bogus", 100, 96f), 0.0001f);
        assertEquals(1f, scale(null, 100, 96f), 0.0001f);
    }

    @Test
    public void columnTrackTextCustomPercentIsTheStoredMultiplier() {
        assertEquals(0.5f, scale("Custom", 50, 96f), 0.0001f);
        assertEquals(1f, scale("Custom", 100, 96f), 0.0001f);
        assertEquals(2.45f, scale("Custom", 245, 96f), 0.0001f);
        assertEquals(4f, scale("Custom", 400, 96f), 0.0001f);
        // Clamped to the setting's own 50-400 stored range.
        assertEquals(0.5f, scale("Custom", 4, 96f), 0.0001f);
        assertEquals(4f, scale("Custom", 999, 96f), 0.0001f);
        // A stored percent only means anything in Custom.
        assertEquals(1f, scale("Normal", 400, 96f), 0.0001f);
    }

    @Test
    public void adaptiveColumnTrackTextScalesOffTheArtSize() {
        // Adaptive replaces the manual mode and its Custom percent entirely.
        assertEquals(1f, adaptive("Normal", 100, 96f), 0.0001f);
        assertEquals(1f, adaptive("Small", 50, 96f), 0.0001f);
        // 120dp art is the readout's "Large" preset bottom size.
        assertEquals(1.25f, adaptive(null, 0, 120f), 0.0001f);
        // Clamped to the readout's own 0.5-2.0 range, like applyTextSize().
        assertEquals(0.5f, adaptive(null, 0, 24f), 0.0001f);
        assertEquals(2f, adaptive(null, 0, 480f), 0.0001f);
    }

    private static float scale(String size, int customPercent, float adaptiveArtDp) {
        return TrackInfoReadoutController.columnTrackTextScale(
                size, customPercent, false, adaptiveArtDp);
    }

    private static float adaptive(String size, int customPercent, float adaptiveArtDp) {
        return TrackInfoReadoutController.columnTrackTextScale(
                size, customPercent, true, adaptiveArtDp);
    }

    @Test
    public void railClearanceClearsVerticalRailOnRight() {
        int gapPx = 12;
        int fallbackPx = 60;
        // Scroll: [100, 900], Rail: [800, 20, 850, 220] (width 50, height 200 -> vertical rail)
        // Overlaps scroll view horizontally. Near edge is railLeft = 800.
        // Expected rightPad: (900 - 800) + 12 = 112, leftPad: 0.
        int[] pads = NativeSpicyShellViewImpl.railClearancePx(100, 900, 800, 20, 850, 220, gapPx, fallbackPx);
        assertEquals(0, pads[0]);
        assertEquals(112, pads[1]);
    }

    @Test
    public void railClearanceClearsVerticalRailOnLeft() {
        int gapPx = 12;
        int fallbackPx = 60;
        // Scroll: [100, 900], Rail: [150, 20, 200, 220] (width 50, height 200 -> vertical rail on left in RTL)
        // Overlaps scroll view horizontally. Near edge is railRight = 200.
        // Expected leftPad: (200 - 100) + 12 = 112, rightPad: 0.
        int[] pads = NativeSpicyShellViewImpl.railClearancePx(100, 900, 150, 20, 200, 220, gapPx, fallbackPx);
        assertEquals(112, pads[0]);
        assertEquals(0, pads[1]);
    }

    @Test
    public void railClearanceFallsBackForHorizontalRow() {
        int gapPx = 12;
        int fallbackPx = 60;
        // Horizontal row (height 40 <= width 200) on right side: falls back to fixed right padding
        int[] padsRight = NativeSpicyShellViewImpl.railClearancePx(100, 900, 650, 20, 850, 60, gapPx, fallbackPx);
        assertEquals(0, padsRight[0]);
        assertEquals(fallbackPx, padsRight[1]);

        // Horizontal row on left side: falls back to fixed left padding
        int[] padsLeft = NativeSpicyShellViewImpl.railClearancePx(100, 900, 150, 20, 350, 60, gapPx, fallbackPx);
        assertEquals(fallbackPx, padsLeft[0]);
        assertEquals(0, padsLeft[1]);
    }

    @Test
    public void railClearanceFallsBackWhenNoOverlap() {
        int gapPx = 12;
        int fallbackPx = 60;
        // Rail completely to the right of scroll view (railLeft >= scrollRight)
        int[] padsRight = NativeSpicyShellViewImpl.railClearancePx(100, 500, 600, 20, 650, 220, gapPx, fallbackPx);
        assertEquals(0, padsRight[0]);
        assertEquals(fallbackPx, padsRight[1]);

        // Rail completely to the left of scroll view (railRight <= scrollLeft)
        int[] padsLeft = NativeSpicyShellViewImpl.railClearancePx(500, 900, 100, 20, 150, 220, gapPx, fallbackPx);
        assertEquals(fallbackPx, padsLeft[0]);
        assertEquals(0, padsLeft[1]);
    }

    @Test
    public void railClearanceFallsBackWhenRailMissing() {
        int gapPx = 12;
        int fallbackPx = 60;
        // Zero dimensions / unmeasured
        int[] padsZero = NativeSpicyShellViewImpl.railClearancePx(100, 900, 0, 0, 0, 0, gapPx, fallbackPx);
        assertEquals(0, padsZero[0]);
        assertEquals(fallbackPx, padsZero[1]);

        // Invalid / inverted dimensions
        int[] padsInvalid = NativeSpicyShellViewImpl.railClearancePx(100, 900, 500, 50, 500, 20, gapPx, fallbackPx);
        assertEquals(0, padsInvalid[0]);
        assertEquals(fallbackPx, padsInvalid[1]);
    }
}
