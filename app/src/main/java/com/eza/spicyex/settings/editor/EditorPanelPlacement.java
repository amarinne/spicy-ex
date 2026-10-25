package com.eza.spicyex.settings.editor;

/**
 * When the layout editor's options panel may move, and when it has to stay where it is.
 *
 * <p>Pure Java on purpose: the rule that keeps the panel off the element it edits is the one
 * decision the placement of the panel hangs on, and it has to be testable without a phone.
 *
 * <p>The panel opens on the side away from the element the selection points at. That is true
 * in per-element mode and in list mode alike: the list opens on the selected element's section,
 * so the panel still has to stay off that element. A panel that kept the edge it happened to
 * have - the default one after a rotation, for instance - could end up on top of the element
 * the list is scrolled to.
 *
 * <p>A rebuild of the content that is already on screen keeps the panel where it is, so an edit
 * updates the panel in place: the same scroll, the same selection, the same edge.
 */
public final class EditorPanelPlacement {

    /** What changed in the panel. */
    public enum Change {
        /** The user picked another element: the panel follows the selection. */
        SELECTION,
        /** The panel's own content is rebuilt after an edit: it updates in place. */
        REBUILD,
        /** The panel switched between the selected element and the list of every element. */
        MODE
    }

    /** What a change does with the panel's edge. */
    public enum Action {
        /** The panel keeps the edge it is on. */
        KEEP,
        /** The panel takes the edge the selection asks for. */
        MOVE
    }

    private EditorPanelPlacement() {
    }

    /**
     * Decides what a change of the panel's content does with the panel's edge.
     *
     * @param change        what changed in the panel.
     * @param sameSelection the content that changed is the selection's own content.
     * @param panelVisible  the panel is on screen.
     * @return MOVE when the panel has to take the edge the selection asks for.
     */
    public static Action decide(Change change, boolean sameSelection, boolean panelVisible) {
        if (change == null) return Action.KEEP;
        switch (change) {
            case MODE:
                // The list opens on the selected element's section, so the panel places itself
                // for that element like the per-element panel does.
                return Action.MOVE;
            case REBUILD:
                // The content on screen is replaced by its new version: the panel updates in
                // place, so the scroll and the edge both stand.
                return Action.KEEP;
            case SELECTION:
            default:
                // A new element moves the panel; the same element again leaves it alone.
                return sameSelection && panelVisible ? Action.KEEP : Action.MOVE;
        }
    }

    /**
     * Whether the panel keeps its edge for a change of its content.
     *
     * @param change        what changed in the panel.
     * @param sameSelection the content that changed is the selection's own content.
     * @param panelVisible  the panel is on screen.
     */
    public static boolean keepsEdge(Change change, boolean sameSelection, boolean panelVisible) {
        return decide(change, sameSelection, panelVisible) == Action.KEEP;
    }
}
