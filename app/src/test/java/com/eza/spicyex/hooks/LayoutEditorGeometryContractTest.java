package com.eza.spicyex.hooks;

import org.junit.Test;

import com.eza.spicyex.settings.editor.EditorPanelPlacement;


import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LayoutEditorGeometryContractTest {
    @Test
    public void toolbarSlotSelectionPrioritizesMinimumOverlapAndEarlierSlot() {
        // Candidate slots: 0 (top-start), 1 (top-centre), 2 (bottom-start), 3 (bottom-centre)
        java.util.List<LyricsLayoutEditController.SlotRect> slots = java.util.Arrays.asList(
                new LyricsLayoutEditController.SlotRect(12, 40, 150, 84),
                new LyricsLayoutEditController.SlotRect(200, 40, 338, 84),
                new LyricsLayoutEditController.SlotRect(12, 600, 150, 644),
                new LyricsLayoutEditController.SlotRect(200, 600, 338, 644)
        );

        // Case 1: no obstacles -> slot 0 chosen (earliest tie)
        org.junit.Assert.assertEquals(0, LyricsLayoutEditController.chooseToolbarSlot(slots, java.util.Collections.emptyList()));
        org.junit.Assert.assertEquals(0, LyricsLayoutEditController.chooseToolbarSlot(slots, null));

        // Case 2: obstacle covers slot 0 -> slot 1 chosen (0 overlap)
        java.util.List<LyricsLayoutEditController.SlotRect> obs1 = java.util.Collections.singletonList(
                new LyricsLayoutEditController.SlotRect(10, 30, 160, 90)
        );
        org.junit.Assert.assertEquals(1, LyricsLayoutEditController.chooseToolbarSlot(slots, obs1));

        // Case 3: obstacles cover slot 0 and 1 -> slot 2 chosen
        java.util.List<LyricsLayoutEditController.SlotRect> obs2 = java.util.Arrays.asList(
                new LyricsLayoutEditController.SlotRect(10, 30, 160, 90),
                new LyricsLayoutEditController.SlotRect(190, 30, 350, 90)
        );
        org.junit.Assert.assertEquals(2, LyricsLayoutEditController.chooseToolbarSlot(slots, obs2));

        // Case 4: obstacles cover slot 0, 1, 2 -> slot 3 chosen
        java.util.List<LyricsLayoutEditController.SlotRect> obs3 = java.util.Arrays.asList(
                new LyricsLayoutEditController.SlotRect(10, 30, 160, 90),
                new LyricsLayoutEditController.SlotRect(190, 30, 350, 90),
                new LyricsLayoutEditController.SlotRect(10, 590, 160, 650)
        );
        org.junit.Assert.assertEquals(3, LyricsLayoutEditController.chooseToolbarSlot(slots, obs3));

        // Case 5: ties with non-zero overlap -> earlier slot chosen
        java.util.List<LyricsLayoutEditController.SlotRect> obs4 = java.util.Arrays.asList(
                new LyricsLayoutEditController.SlotRect(0, 0, 200, 200), // covers slot 0 heavily
                new LyricsLayoutEditController.SlotRect(200, 40, 220, 50), // slot 1: 20x10 = 200
                new LyricsLayoutEditController.SlotRect(12, 600, 32, 610), // slot 2: 20x10 = 200
                new LyricsLayoutEditController.SlotRect(200, 600, 300, 700) // covers slot 3 heavily
        );
        // Between slot 1 and slot 2 (both 200), tie goes to slot 1
        org.junit.Assert.assertEquals(1, LyricsLayoutEditController.chooseToolbarSlot(slots, obs4));

        // Case 6: edge-touching is not overlapping (0 area)
        java.util.List<LyricsLayoutEditController.SlotRect> obsTouching = java.util.Collections.singletonList(
                new LyricsLayoutEditController.SlotRect(0, 0, 12, 40) // touches left and top of slot 0
        );
        org.junit.Assert.assertEquals(0, LyricsLayoutEditController.chooseToolbarSlot(slots, obsTouching));
    }

    // -- compact-screen slot and sheet geometry ---------------------------------
    // The numbers below are the ones the device probe measured (2026-10-22):
    //   Xiaomi 14, portrait, 1200 x 2670 at density 3.14375
    //   Fold 3, portrait, 1768 x 2208 at density 3

    private static final int XIAOMI_WIDTH = 1200;
    private static final int XIAOMI_HEIGHT = 2670;
    private static final int XIAOMI_START_X = 38;    // dp(12)
    private static final int XIAOMI_TOP_Y = 159;     // status bar 134 + dp(8)
    private static final int XIAOMI_BOTTOM_Y = 2494;  // 2670 - nav 0 - dp(12) - dp(44)
    private static final int XIAOMI_ROW_HEIGHT = 138; // dp(44)
    private static final int XIAOMI_GAP = 25;         // dp(8)

    private static final int FOLD_WIDTH = 1768;
    private static final int FOLD_HEIGHT = 2208;
    private static final int FOLD_START_X = 36;      // dp(12)
    private static final int FOLD_TOP_Y = 112;       // status bar 88 + dp(8)
    private static final int FOLD_BOTTOM_Y = 1995;   // 2208 - nav 45 - dp(12) - dp(44)
    private static final int FOLD_ROW_HEIGHT = 132;  // dp(44)
    private static final int FOLD_GAP = 24;          // dp(8)

    /** The live top controls on the Xiaomi: a row across the top right of the screen. */
    private static final LyricsLayoutEditController.SlotRect XIAOMI_DOCK =
            new LyricsLayoutEditController.SlotRect(360, 184, 1150, 322);
    /** The live top controls on the Fold: a rail down the right edge. */
    private static final LyricsLayoutEditController.SlotRect FOLD_DOCK =
            new LyricsLayoutEditController.SlotRect(1588, 136, 1720, 736);

    /** The candidate slots, built the way the editor builds them: the four band slots, then the
     *  band right under the live top controls. */
    private static java.util.List<LyricsLayoutEditController.SlotRect> slots(int overlayWidth,
            int startX, int topY, int bottomY, int rowWidth, int rowHeight, int gap,
            LyricsLayoutEditController.SlotRect chrome) {
        int centerX = Math.max(0, (overlayWidth - rowWidth) / 2);
        java.util.List<LyricsLayoutEditController.SlotRect> slots =
                new java.util.ArrayList<>(java.util.Arrays.asList(
                        new LyricsLayoutEditController.SlotRect(startX, topY,
                                startX + rowWidth, topY + rowHeight),
                        new LyricsLayoutEditController.SlotRect(centerX, topY,
                                centerX + rowWidth, topY + rowHeight),
                        new LyricsLayoutEditController.SlotRect(startX, bottomY,
                                startX + rowWidth, bottomY + rowHeight),
                        new LyricsLayoutEditController.SlotRect(centerX, bottomY,
                                centerX + rowWidth, bottomY + rowHeight)));
        if (chrome != null && chrome.bottom > 0) {
            int belowTop = chrome.bottom + gap;
            slots.add(new LyricsLayoutEditController.SlotRect(startX, belowTop,
                    startX + rowWidth, belowTop + rowHeight));
        }
        return slots;
    }

    private static java.util.List<LyricsLayoutEditController.SlotRect> xiaomiSlots(int rowWidth) {
        return slots(XIAOMI_WIDTH, XIAOMI_START_X, XIAOMI_TOP_Y, XIAOMI_BOTTOM_Y,
                rowWidth, XIAOMI_ROW_HEIGHT, XIAOMI_GAP, XIAOMI_DOCK);
    }

    private static java.util.List<LyricsLayoutEditController.SlotRect> foldSlots(int rowWidth) {
        return slots(FOLD_WIDTH, FOLD_START_X, FOLD_TOP_Y, FOLD_BOTTOM_Y,
                rowWidth, FOLD_ROW_HEIGHT, FOLD_GAP, FOLD_DOCK);
    }

    /** What the editor sees on the Xiaomi: artwork, track text, top controls, both chips. */
    private static java.util.List<LyricsLayoutEditController.SlotRect> xiaomiObstacles() {
        return java.util.Arrays.asList(
                new LyricsLayoutEditController.SlotRect(50, 2161, 446, 2557),    // artwork
                new LyricsLayoutEditController.SlotRect(484, 2161, 1150, 2557),  // track text
                XIAOMI_DOCK,
                new LyricsLayoutEditController.SlotRect(1012, 1834, 1150, 1972), // chip.skip
                new LyricsLayoutEditController.SlotRect(1012, 1998, 1150, 2136)  // chip.follow
        );
    }

    /** What the editor sees on the Fold: artwork, track text, the top-controls rail, both chips. */
    private static java.util.List<LyricsLayoutEditController.SlotRect> foldObstacles() {
        return java.util.Arrays.asList(
                new LyricsLayoutEditController.SlotRect(60, 196, 276, 412),     // artwork
                new LyricsLayoutEditController.SlotRect(312, 196, 1552, 412),   // track text
                FOLD_DOCK,
                new LyricsLayoutEditController.SlotRect(1588, 1848, 1720, 1980),// chip.skip
                new LyricsLayoutEditController.SlotRect(1588, 2004, 1720, 2136) // chip.follow
        );
    }

    /** The rule the editor runs on the Xiaomi: the top controls may not be touched at all. */
    private static LyricsLayoutEditController.ToolbarConstraints xiaomiRules() {
        return new LyricsLayoutEditController.ToolbarConstraints(
                java.util.Collections.singletonList(XIAOMI_DOCK),
                new LyricsLayoutEditController.SlotRect(0, 0, XIAOMI_WIDTH, XIAOMI_HEIGHT));
    }

    @Test
    public void dockCollisionRejectsTheTopSlotsOnACompactPortraitScreen() {
        java.util.List<LyricsLayoutEditController.SlotRect> obstacles = xiaomiObstacles();
        LyricsLayoutEditController.ToolbarConstraints rules = xiaomiRules();

        // The row as the device measured it: Done [38,159,176,297], Reset [195,159,333,297],
        // Demo [352,159,534,297] in the top-start slot, and the demo button on the dock.
        java.util.List<LyricsLayoutEditController.SlotRect> measured = slots(XIAOMI_WIDTH,
                XIAOMI_START_X, XIAOMI_TOP_Y, XIAOMI_BOTTOM_Y, 496, XIAOMI_ROW_HEIGHT,
                XIAOMI_GAP, null);
        assertTrue(measured.get(0).intersects(XIAOMI_DOCK));
        // Without the dock as a hard constraint the least-overlapping slot is that same
        // top-start slot: the rule trades overlap area and loses.
        assertEquals(0, LyricsLayoutEditController.chooseToolbarSlot(measured, obstacles));
        // With the dock forbidden the row leaves the top band.
        int chosen = LyricsLayoutEditController.chooseToolbarSlot(measured, obstacles, rules);
        assertEquals(2, chosen);
        assertFalse(measured.get(chosen).intersects(XIAOMI_DOCK));
    }

    @Test
    public void theCompactScreenRowTakesTheBandUnderTheTopControls() {
        java.util.List<LyricsLayoutEditController.SlotRect> obstacles = xiaomiObstacles();
        LyricsLayoutEditController.ToolbarConstraints rules = xiaomiRules();

        // The list-mode row is 653px wide, and the band under the top controls is the one place
        // left where it fits without covering anything.
        java.util.List<LyricsLayoutEditController.SlotRect> wide = xiaomiSlots(653);
        int chosen = LyricsLayoutEditController.chooseToolbarSlot(wide, obstacles, rules);
        assertEquals(4, chosen);

        // The band is measured from the controls' own bottom edge, not from a fixed offset.
        LyricsLayoutEditController.SlotRect row = wide.get(chosen);
        assertEquals(38, row.left);
        assertEquals(347, row.top);
        assertEquals(691, row.right);
        assertEquals(485, row.bottom);

        // Nothing the user reads is under the row: not the controls, not the artwork, not the
        // track text, not the chips.
        for (LyricsLayoutEditController.SlotRect obstacle : obstacles) {
            assertFalse(row.intersects(obstacle));
        }
    }

    @Test
    public void theFoldKeepsItsBottomStartSlot() {
        java.util.List<LyricsLayoutEditController.SlotRect> obstacles = foldObstacles();
        LyricsLayoutEditController.ToolbarConstraints rules =
                new LyricsLayoutEditController.ToolbarConstraints(
                        java.util.Collections.singletonList(FOLD_DOCK),
                        new LyricsLayoutEditController.SlotRect(0, 0, FOLD_WIDTH, FOLD_HEIGHT));

        // The Fold is wide enough for the row to miss the top controls, so the rule with and
        // without the dock constraint both keep the slot the row already sits in - including
        // with the extra candidate under the rail, which is free but comes later.
        for (int rowWidth : new int[]{447, 597}) {
            java.util.List<LyricsLayoutEditController.SlotRect> candidates = foldSlots(rowWidth);
            assertEquals(2, LyricsLayoutEditController.chooseToolbarSlot(candidates, obstacles));
            assertEquals(2, LyricsLayoutEditController.chooseToolbarSlot(candidates, obstacles, rules));
            assertFalse(candidates.get(2).intersects(FOLD_DOCK));
        }
    }

    @Test
    public void aScreenWithNoFreeSlotTakesTheLeastOverlappingOne() {
        // Every slot touches the forbidden rect: the row still has to go somewhere, so it takes
        // the slot that overlaps the other obstacles least.
        java.util.List<LyricsLayoutEditController.SlotRect> candidates = java.util.Arrays.asList(
                new LyricsLayoutEditController.SlotRect(0, 0, 200, 100),
                new LyricsLayoutEditController.SlotRect(0, 200, 200, 300),
                new LyricsLayoutEditController.SlotRect(0, 400, 200, 500)
        );
        LyricsLayoutEditController.ToolbarConstraints rules =
                new LyricsLayoutEditController.ToolbarConstraints(
                        java.util.Collections.singletonList(
                                new LyricsLayoutEditController.SlotRect(0, 0, 200, 500)),
                        new LyricsLayoutEditController.SlotRect(0, 0, 200, 500));
        java.util.List<LyricsLayoutEditController.SlotRect> obstacles = java.util.Collections.singletonList(
                new LyricsLayoutEditController.SlotRect(0, 50, 60, 150));
        // Slot 0 overlaps the obstacle (60 x 50), slots 1 and 2 do not; the earlier one wins.
        assertEquals(1, LyricsLayoutEditController.chooseToolbarSlot(candidates, obstacles, rules));
    }

    @Test
    public void aSlotThatPokesOutOfTheSafeAreaLosesATie() {
        // A slot that pokes out of the safe area is worse than one that does not, but only when
        // the overlap is the same.
        java.util.List<LyricsLayoutEditController.SlotRect> candidates = java.util.Arrays.asList(
                new LyricsLayoutEditController.SlotRect(0, 0, 300, 100),  // outside the safe area
                new LyricsLayoutEditController.SlotRect(0, 200, 200, 300)  // inside it
        );
        LyricsLayoutEditController.ToolbarConstraints rules =
                new LyricsLayoutEditController.ToolbarConstraints(
                        java.util.Collections.<LyricsLayoutEditController.SlotRect>emptyList(),
                        new LyricsLayoutEditController.SlotRect(0, 0, 200, 500));
        assertEquals(1, LyricsLayoutEditController.chooseToolbarSlot(candidates,
                java.util.Collections.<LyricsLayoutEditController.SlotRect>emptyList(), rules));
    }

    @Test
    public void aRejectedSlotKeepsTheRowInsideTheSafeArea() {
        // Every slot is rejected, and they overlap the other obstacles equally: the one that
        // stays inside the safe area is the one that goes.
        java.util.List<LyricsLayoutEditController.SlotRect> candidates = java.util.Arrays.asList(
                new LyricsLayoutEditController.SlotRect(0, 0, 300, 100),  // outside the safe area
                new LyricsLayoutEditController.SlotRect(0, 200, 200, 300)  // inside it
        );
        LyricsLayoutEditController.ToolbarConstraints rules =
                new LyricsLayoutEditController.ToolbarConstraints(
                        java.util.Collections.singletonList(
                                new LyricsLayoutEditController.SlotRect(0, 0, 300, 500)),
                        new LyricsLayoutEditController.SlotRect(0, 0, 200, 500));
        assertEquals(1, LyricsLayoutEditController.chooseToolbarSlot(candidates,
                java.util.Collections.<LyricsLayoutEditController.SlotRect>emptyList(), rules));
    }

    @Test
    public void thePanelStartsBelowTheTopControlsItSpans() {
        // Xiaomi 14: the top controls are [360,184,1150,322] and the editor's row lands under
        // them at [38,347,691,485]. The panel spans the screen width, so it starts below both.
        LyricsLayoutEditController.SlotRect row =
                new LyricsLayoutEditController.SlotRect(38, 347, 691, 485);
        assertEquals(510, LyricsLayoutEditController.panelTopInset(38, XIAOMI_GAP,
                XIAOMI_HEIGHT, XIAOMI_DOCK, row, 38, 1162));
        // The controls alone already push it down: a row that moved out of the band does not
        // hand the panel back the controls it was covering.
        assertEquals(347, LyricsLayoutEditController.panelTopInset(38, XIAOMI_GAP,
                XIAOMI_HEIGHT, XIAOMI_DOCK, null, 38, 1162));
        // A panel that does not reach the controls keeps the plain screen inset.
        assertEquals(38, LyricsLayoutEditController.panelTopInset(38, XIAOMI_GAP,
                XIAOMI_HEIGHT, XIAOMI_DOCK, null, 0, 300));
    }

    @Test
    public void theFoldPanelKeepsItsTopEdge() {
        // Fold 3: the top controls are a rail down the right edge, [1588,136,1720,736], and the
        // editor's row stays in the bottom band at [36,1995,633,2127]. The panel spans
        // [318,1449] and reaches neither of them, so it keeps the plain inset it always had.
        LyricsLayoutEditController.SlotRect row =
                new LyricsLayoutEditController.SlotRect(36, 1995, 633, 2127);
        assertEquals(36, LyricsLayoutEditController.panelTopInset(36, FOLD_GAP,
                FOLD_HEIGHT, FOLD_DOCK, row, 318, 1449));
    }

    @Test
    public void theCompactScreenSheetStopsAboveTheStackedSkipChip() {
        // The Xiaomi's row lands under the top controls, so the top-anchored panel starts below
        // the row at 485 + dp(8) = 510. Its planned height is the overlay's 60 %: 1602, so the
        // planned sheet is [38,510,1162,2112].
        int[] sheet = {38, 510, 1162, 510 + 1602};
        int[] chip = {1012, 1834, 1150, 1972};
        int capped = LyricsLayoutEditController.capPanelHeightForTarget(1602, sheet, chip,
                LyricsLayoutEditController.SheetPlacement.TOP, 503);
        assertEquals(1324, capped);
        assertEquals(chip[1], sheet[1] + capped);
        // The sheet it leaves behind covers neither the chip nor the top controls.
        int[] cappedSheet = {38, 510, 1162, 510 + capped};
        assertFalse(intersects(cappedSheet, chip));
        assertFalse(intersects(cappedSheet, new int[]{360, 184, 1150, 322}));
    }

    @Test
    public void thePanelStopsBeforeTheElementItEdits() {
        // Xiaomi 14: the sheet is [38,322,1162,1924] and the stacked skip chip is
        // [1012,1834,1150,1972]. The sheet's lower edge is 90 px into a 138 px tall chip - 65 %
        // of it, over the spec's half - so the sheet has to stop above the chip.
        int[] sheet = {38, 322, 1162, 1924};
        int[] chip = {1012, 1834, 1150, 1972};
        int capped = LyricsLayoutEditController.capPanelHeightForTarget(1602, sheet, chip,
                LyricsLayoutEditController.SheetPlacement.TOP, 503);
        assertEquals(1512, capped);
        assertEquals(chip[1], sheet[1] + capped);
    }

    @Test
    public void thePanelKeepsItsHeightWhenTheElementIsOnTheFarSide() {
        // Fold 3: the top-anchored sheet is [344,36,1424,1361] and the skip chip is on the other
        // side of it, [1588,1848,1720,1980]. There is nothing to clear.
        int[] sheet = {344, 36, 1424, 1361};
        int[] chip = {1588, 1848, 1720, 1980};
        assertEquals(1325, LyricsLayoutEditController.capPanelHeightForTarget(1325, sheet, chip,
                LyricsLayoutEditController.SheetPlacement.TOP, 480));
        // The Fold's focus line is under the same sheet: the panel stops above it.
        int[] focus = {0, 1038, 1768, 1170};
        assertEquals(1002, LyricsLayoutEditController.capPanelHeightForTarget(1325, sheet, focus,
                LyricsLayoutEditController.SheetPlacement.TOP, 480));
    }

    @Test
    public void aSheetThatBarelyTouchesTheElementIsNotCapped() {
        // Six pixels into the chip is 4 % of it: under the spec's share, so the panel keeps its
        // height and the rule stays where it is.
        int[] sheet = {38, 510, 1162, 1840};
        int[] chip = {1012, 1834, 1150, 1972};
        assertEquals(1330, LyricsLayoutEditController.capPanelHeightForTarget(1330, sheet, chip,
                LyricsLayoutEditController.SheetPlacement.TOP, 503));
    }

    @Test
    public void aBottomAnchoredPanelStopsAboveTheElementItEdits() {
        // A panel on the bottom edge grows upwards, so its upper edge is the one that moves.
        // The Xiaomi's bottom-anchored sheet is [38,1030,1162,2632] and its skip chip is
        // [1012,1834,1150,1972].
        int[] sheet = {38, 1030, 1162, 2632};
        int[] skip = {1012, 1834, 1150, 1972};
        int capped = LyricsLayoutEditController.capPanelHeightForTarget(1602, sheet, skip,
                LyricsLayoutEditController.SheetPlacement.BOTTOM, 503);
        assertEquals(660, capped);
        assertEquals(skip[3], sheet[3] - capped);
    }

    @Test
    public void aPanelThatWouldBecomeTooShortKeepsItsMinimumHeight() {
        // The element fills the lower half: clearing it would leave a sliver, and a panel too
        // short to show its controls is worse than one that still covers the element.
        int[] sheet = {38, 1030, 1162, 2632};
        int[] element = {50, 2000, 1150, 2600};
        assertEquals(503, LyricsLayoutEditController.capPanelHeightForTarget(1602, sheet, element,
                LyricsLayoutEditController.SheetPlacement.BOTTOM, 503));
    }

    /** Strict: shared edges are touching, they do not overlap. */
    private static boolean intersects(int[] a, int[] b) {
        return a[0] < b[2] && a[2] > b[0] && a[1] < b[3] && a[3] > b[1];
    }

    // -- the Fold landscape list-mode panel (measured 2026-10-22) -------------
    // Fold 3, landscape: overlay 2208 x 1768 at density 3, two-column layout. The editor's
    // row sits in the top band, the Skip and Follow chips in the trailing column.

    private static final int FOLD_LANDSCAPE_WIDTH = 2208;
    private static final int FOLD_LANDSCAPE_HEIGHT = 1768;
    /** The skip chip as the device measured it in landscape. */
    private static final int[] FOLD_LANDSCAPE_SKIP = {2028, 1408, 2160, 1540};

    @Test
    public void listModePlacesThePanelAwayFromTheSelectedChip() {
        // The mode switch into list mode places the panel for the selection: the list opens
        // on the selected element's section, so the panel has to stay off that element.
        assertEquals(EditorPanelPlacement.Action.MOVE, EditorPanelPlacement.decide(
                EditorPanelPlacement.Change.MODE, true, true));
        // The chip is in the trailing half, so the panel goes to the start edge - the same
        // edge the per-element panel takes for the same selection.
        LyricsLayoutEditController.SheetPlacement placement =
                LyricsLayoutEditController.SheetPlacement.choose(FOLD_LANDSCAPE_WIDTH,
                        FOLD_LANDSCAPE_HEIGHT, FOLD_LANDSCAPE_SKIP, true, false);
        assertEquals(LyricsLayoutEditController.SheetPlacement.START, placement);
        // The panel as the device measured it in element mode: [36,626,1116,1687].
        int[] sheet = {36, 626, 1116, 1687};
        // The chip is on the far side of it, so the height cap has nothing to clear.
        assertEquals(1061, LyricsLayoutEditController.capPanelHeightForTarget(1061, sheet,
                FOLD_LANDSCAPE_SKIP, placement, 480));
        // And the probe's own rule agrees: the panel does not cover the chip.
        LayoutProbeReport report = new LayoutProbeReport();
        report.rect("sheet", sheet);
        report.rect("chip.skip", FOLD_LANDSCAPE_SKIP);
        report.text("selected", "skip");
        assertFalse(report.violations().contains("R6 sheet_covers_target skip"));
    }

    @Test
    public void theOldListModePlacementCoveredTheChipItWasEditing() {
        // Before the fix the mode switch kept the panel's edge, so the panel stayed on the
        // default END edge: [1092,1207,2172,1687] over the skip chip [2028,1408,2160,1540].
        // The whole chip was under the panel, which is the R6 failure the device measured.
        int[] sheet = {1092, 1207, 2172, 1687};
        LayoutProbeReport report = new LayoutProbeReport();
        report.rect("sheet", sheet);
        report.rect("chip.skip", FOLD_LANDSCAPE_SKIP);
        report.text("selected", "skip");
        assertTrue(report.violations().contains("R6 sheet_covers_target skip"));
        // The minimum height is what made the cap powerless there: clearing the chip would
        // have left a sliver, so the panel kept its height and covered it all the same.
        assertEquals(480, LyricsLayoutEditController.capPanelHeightForTarget(1061, sheet,
                FOLD_LANDSCAPE_SKIP, LyricsLayoutEditController.SheetPlacement.END, 480));
    }
}
