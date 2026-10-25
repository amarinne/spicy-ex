package com.eza.spicyex.settings.editor;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The chip row's wrapping plan. The Fold measured two options whose label was cut off
 * ("Adaptive arrow" on the follow chip, "Animated texture" on the background style), so the
 * plan has to give a long label room instead of a share of the row it cannot use.
 */
public class ChipRowPlanTest {
    /** The follow chip's icon row, as the editor labels it. */
    private static final List<String> FOLLOW_CHIP_ICON =
            Arrays.asList("Adaptive arrow", "Static arrow", "Waveform");
    /** The background style row, as the editor labels it. */
    private static final List<String> BACKGROUND_STYLE =
            Arrays.asList("Gradient", "Static texture", "Animated texture");
    /** The scope and fill rows, whose labels are long enough to need a line each. */
    private static final List<String> LINE_SYNC_FILL =
            Arrays.asList("Top to bottom", "Left to right (block)", "Left to right (sentence)");
    private static final List<String> WORD_BOUNCE =
            Arrays.asList("Off", "Word/syllable synced only", "All synced rows");

    @Test public void aLongLabelGetsRoomInsteadOfAShareItCannotUse() {
        assertEquals(Arrays.asList(Arrays.asList(0, 1), Arrays.asList(2)),
                ChipRowPlan.plan(FOLLOW_CHIP_ICON));
        assertEquals(Arrays.asList(Arrays.asList(0, 1), Arrays.asList(2)),
                ChipRowPlan.plan(BACKGROUND_STYLE));
    }

    @Test public void everyPlannedLineHoldsEveryLabelOnIt() {
        List<List<String>> rows = Arrays.asList(FOLLOW_CHIP_ICON, BACKGROUND_STYLE, LINE_SYNC_FILL,
                WORD_BOUNCE, Arrays.asList("Left", "Center", "Right"),
                Arrays.asList("Auto", "Top", "Center", "Bottom"),
                Arrays.asList("Phrase zoom", "Word zoom", "Phrase lift", "Word lift", "Apple lift"));
        for (List<String> labels : rows) {
            for (List<Integer> line : ChipRowPlan.plan(labels)) {
                for (int index : line) {
                    assertTrue(labels.get(index) + " does not fit a line of " + line.size(),
                            ChipRowPlan.holds(labels.get(index), line.size()));
                }
            }
        }
    }

    @Test public void aShortRowKeepsItsOneLineOfThree() {
        assertEquals(Arrays.asList(Arrays.asList(0, 1, 2)),
                ChipRowPlan.plan(Arrays.asList("Left", "Center", "Right")));
    }

    @Test public void aLongerRowKeepsItsTwoChipsPerLine() {
        assertEquals(Arrays.asList(Arrays.asList(0, 1), Arrays.asList(2, 3)),
                ChipRowPlan.plan(Arrays.asList("Auto", "Top", "Center", "Bottom")));
    }

    @Test public void theScopeAndFillRowsStayOneChipPerLine() {
        for (List<Integer> line : ChipRowPlan.plan(LINE_SYNC_FILL)) {
            assertEquals("Every scope and fill label gets its own line", 1, line.size());
        }
        for (List<Integer> line : ChipRowPlan.plan(WORD_BOUNCE)) {
            assertEquals("Every word bounce label gets its own line", 1, line.size());
        }
    }

    @Test public void aLabelLongerThanTheWholeRowStillGetsALine() {
        List<String> labels = Arrays.asList("A very long label that no line can hold");
        List<List<Integer>> plan = ChipRowPlan.plan(labels);
        assertEquals(1, plan.size());
        assertEquals(Arrays.asList(0), plan.get(0));
        assertTrue("A label that fits no line wraps inside its chip instead of being cut",
                !ChipRowPlan.holds(labels.get(0), 1));
    }
}
