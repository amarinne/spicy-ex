package com.eza.spicyex.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The confirming-dialog dirty rule: Save arms only while pending differs from stored. */
public class ConfirmingSelectionTest {
    @Test
    public void freshSelectionIsCleanAndHighlightsInitial() {
        PanelDialog.ConfirmingSelection selection = new PanelDialog.ConfirmingSelection("en");
        assertFalse(selection.isDirty());
        assertEquals("en", selection.pending());
        assertTrue(selection.isHighlighted("en"));
        assertFalse(selection.isHighlighted("zh-CN"));
    }

    @Test
    public void selectingAnotherValueDirtiesAndMovesHighlight() {
        PanelDialog.ConfirmingSelection selection = new PanelDialog.ConfirmingSelection("en");
        selection.select("zh-CN");
        assertTrue(selection.isDirty());
        assertEquals("zh-CN", selection.pending());
        assertTrue(selection.isHighlighted("zh-CN"));
        assertFalse(selection.isHighlighted("en"));
    }

    @Test
    public void reselectingInitialDisarms() {
        PanelDialog.ConfirmingSelection selection = new PanelDialog.ConfirmingSelection("en");
        selection.select("zh-CN");
        selection.select("en");
        assertFalse(selection.isDirty());
        assertTrue(selection.isHighlighted("en"));
    }

    @Test
    public void nullPendingNeverCountsAsDirty() {
        PanelDialog.ConfirmingSelection selection = new PanelDialog.ConfirmingSelection("en");
        selection.select(null);
        assertFalse(selection.isDirty());
        assertFalse(selection.isHighlighted("en"));
    }

    @Test
    public void nullInitialDirtiesOnFirstPick() {
        PanelDialog.ConfirmingSelection selection = new PanelDialog.ConfirmingSelection(null);
        assertFalse(selection.isDirty());
        selection.select("en");
        assertTrue(selection.isDirty());
    }
}
