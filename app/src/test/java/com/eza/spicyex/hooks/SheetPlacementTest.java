package com.eza.spicyex.hooks;

import static com.eza.spicyex.hooks.LyricsLayoutEditController.SheetPlacement.BOTTOM;
import static com.eza.spicyex.hooks.LyricsLayoutEditController.SheetPlacement.END;
import static com.eza.spicyex.hooks.LyricsLayoutEditController.SheetPlacement.START;
import static com.eza.spicyex.hooks.LyricsLayoutEditController.SheetPlacement.TOP;
import static org.junit.Assert.assertEquals;

import com.eza.spicyex.hooks.LyricsLayoutEditController.SheetPlacement;

import org.junit.Test;

/**
 * The sheet covering the element it edits is the whole point of this rule, so each half of the
 * overlay gets a case for "the target is on my side" and one for "it is on the other", and the
 * cases that must NOT move the sheet - no target, card mode, an unmeasured overlay - get cases
 * too. A rule that always fired would be indistinguishable from a rule that is broken.
 */
public class SheetPlacementTest {

    /** A portrait phone overlay. */
    private static final int PHONE_W = 1080;
    private static final int PHONE_H = 2400;
    /** An unfolded foldable / tablet overlay, wide enough for a side sheet. */
    private static final int WIDE_W = 2560;
    private static final int WIDE_H = 1600;

    private static int[] rect(int left, int top, int right, int bottom) {
        return new int[]{left, top, right, bottom};
    }

    // Bottom sheet -----------------------------------------------------------

    @Test
    public void bottomSheetGoesToTheTopWhenTheTargetIsInTheLowerHalf() {
        // The Skip and Follow chips live at the bottom: a bottom sheet would hide both.
        assertEquals(TOP, SheetPlacement.choose(PHONE_W, PHONE_H,
                rect(400, 2100, 680, 2200), false, false));
    }

    @Test
    public void bottomSheetStaysAtTheBottomWhenTheTargetIsInTheUpperHalf() {
        assertEquals(BOTTOM, SheetPlacement.choose(PHONE_W, PHONE_H,
                rect(40, 200, 340, 500), false, false));
    }

    @Test
    public void bottomSheetTreatsTheExactMidlineAsTheFarHalf() {
        // Centre y lands exactly on the midline (1200). It must be treated as the lower half,
        // not fall back to the default by rounding.
        assertEquals(TOP, SheetPlacement.choose(PHONE_W, PHONE_H,
                rect(40, 1100, 340, 1300), false, false));
    }

    // Side sheet -------------------------------------------------------------

    @Test
    public void sideSheetGoesToTheStartWhenTheTargetIsOnTheTrailingHalf() {
        // The dock sitting on the right: a sheet on the right covers it.
        assertEquals(START, SheetPlacement.choose(WIDE_W, WIDE_H,
                rect(2200, 60, 2500, 140), true, false));
    }

    @Test
    public void sideSheetStaysAtTheEndWhenTheTargetIsOnTheLeadingHalf() {
        assertEquals(END, SheetPlacement.choose(WIDE_W, WIDE_H,
                rect(60, 60, 360, 140), true, false));
    }

    @Test
    public void sideSheetTreatsTheExactVerticalMidlineAsTheTrailingHalf() {
        assertEquals(START, SheetPlacement.choose(WIDE_W, WIDE_H,
                rect(1180, 60, 1380, 140), true, false));
    }

    // Defaults the rule must not disturb --------------------------------------

    @Test
    public void noTargetKeepsTheDefaultEdgeOnBothPresentations() {
        // Lyrics, Background and the card have no single rect to avoid.
        assertEquals(BOTTOM, SheetPlacement.choose(PHONE_W, PHONE_H, null, false, false));
        assertEquals(END, SheetPlacement.choose(WIDE_W, WIDE_H, null, true, false));
    }

    @Test
    public void cardModeKeepsTheDefaultEdgeEvenWithATargetOnTheFarHalf() {
        assertEquals(END, SheetPlacement.choose(WIDE_W, WIDE_H,
                rect(2200, 700, 2500, 1500), true, true));
        assertEquals(BOTTOM, SheetPlacement.choose(PHONE_W, PHONE_H,
                rect(400, 2100, 680, 2200), false, true));
    }

    @Test
    public void anUnmeasuredOverlayKeepsTheDefaultEdge() {
        // The overlay has no size yet (first frame): there is no "half" to compare against.
        assertEquals(BOTTOM, SheetPlacement.choose(0, 0, rect(400, 2100, 680, 2200), false, false));
        assertEquals(END, SheetPlacement.choose(0, 0, rect(2200, 60, 2500, 140), true, false));
    }

    @Test
    public void aDegenerateTargetKeepsTheDefaultEdge() {
        // A zero-area rect is as good as no reading at all; its "centre" is not on screen.
        assertEquals(BOTTOM, SheetPlacement.choose(PHONE_W, PHONE_H,
                rect(400, 2100, 400, 2100), false, false));
        assertEquals(END, SheetPlacement.choose(WIDE_W, WIDE_H,
                rect(2200, 60, 2200, 60), true, false));
        // Inverted bounds, same verdict.
        assertEquals(BOTTOM, SheetPlacement.choose(PHONE_W, PHONE_H,
                rect(400, 2200, 680, 2100), false, false));
    }

    @Test
    public void aTargetSpanningBothHalvesFollowsItsCentre() {
        // The focus line spans nearly the whole screen, so it covers the top and the bottom
        // equally: what decides is that its centre is exactly on the midline.
        assertEquals(TOP, SheetPlacement.choose(PHONE_W, PHONE_H,
                rect(0, 200, PHONE_W, 2200), false, false));
            // A wide element on a side sheet: its centre is still in the leading half, so the sheet
        // keeps its default edge even though the rect reaches well past the midline.
        assertEquals(END, SheetPlacement.choose(WIDE_W, WIDE_H,
                rect(0, 0, 2000, 800), true, false));
    }
}
