package com.eza.spicyex.settings.editor;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The panel's edge is the one decision the "the panel may not cover the element it edits" rule
 * hangs on, so it is decided here and not inside the Android session.
 */
public class EditorPanelPlacementTest {

    @Test
    public void switchingToListModePlacesThePanelForTheSelection() {
        // The regression: the list opens on the selected element's section, so the panel has
        // to place itself for that element, exactly like the per-element panel does. A panel
        // that kept the edge it happened to have - the default one after a rotation, for
        // instance - can end up on top of the element it is editing.
        assertEquals(EditorPanelPlacement.Action.MOVE, EditorPanelPlacement.decide(
                EditorPanelPlacement.Change.MODE, true, true));
        assertEquals(EditorPanelPlacement.Action.MOVE, EditorPanelPlacement.decide(
                EditorPanelPlacement.Change.MODE, false, true));
        assertEquals(EditorPanelPlacement.Action.MOVE, EditorPanelPlacement.decide(
                EditorPanelPlacement.Change.MODE, true, false));
    }

    @Test
    public void aRebuildKeepsTheEdgeSoTheEditUpdatesInPlace() {
        // An edit replaces the content that is already on screen: the panel, its scroll and
        // its edge all stand.
        assertEquals(EditorPanelPlacement.Action.KEEP, EditorPanelPlacement.decide(
                EditorPanelPlacement.Change.REBUILD, true, true));
        assertEquals(EditorPanelPlacement.Action.KEEP, EditorPanelPlacement.decide(
                EditorPanelPlacement.Change.REBUILD, false, true));
        assertEquals(EditorPanelPlacement.Action.KEEP, EditorPanelPlacement.decide(
                EditorPanelPlacement.Change.REBUILD, true, false));
    }

    @Test
    public void aNewElementMovesThePanel() {
        assertEquals(EditorPanelPlacement.Action.MOVE, EditorPanelPlacement.decide(
                EditorPanelPlacement.Change.SELECTION, false, true));
        assertEquals(EditorPanelPlacement.Action.MOVE, EditorPanelPlacement.decide(
                EditorPanelPlacement.Change.SELECTION, false, false));
    }

    @Test
    public void theSameElementAgainLeavesThePanelWhereItIs() {
        // Picking the element that is already selected refills the panel where it stands.
        assertEquals(EditorPanelPlacement.Action.KEEP, EditorPanelPlacement.decide(
                EditorPanelPlacement.Change.SELECTION, true, true));
        // A hidden panel has no edge to keep: it is placed when it comes back.
        assertEquals(EditorPanelPlacement.Action.MOVE, EditorPanelPlacement.decide(
                EditorPanelPlacement.Change.SELECTION, true, false));
    }

    @Test
    public void keepsEdgeAgreesWithDecide() {
        assertTrue(EditorPanelPlacement.keepsEdge(EditorPanelPlacement.Change.REBUILD, true, true));
        assertFalse(EditorPanelPlacement.keepsEdge(EditorPanelPlacement.Change.MODE, true, true));
        assertFalse(EditorPanelPlacement.keepsEdge(EditorPanelPlacement.Change.SELECTION, false, true));
    }
}
