package com.eza.spicyex.settings.values;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The panel media controls mode: the gestures each mode allows. */
public class PanelMediaModeTest {
    @Test
    public void offDisablesAllGestures() {
        assertFalse(PanelMediaMode.OFF.gesturesEnabled());
        assertFalse(PanelMediaMode.OFF.revealsOnSingleTap());
    }

    @Test
    public void singleTapRevealsOverlay() {
        assertTrue(PanelMediaMode.SINGLE_TAP.gesturesEnabled());
        assertTrue(PanelMediaMode.SINGLE_TAP.revealsOnSingleTap());
    }

    @Test
    public void doubleTapSkipsOverlayButKeepsGestures() {
        assertTrue(PanelMediaMode.DOUBLE_TAP.gesturesEnabled());
        assertFalse(PanelMediaMode.DOUBLE_TAP.revealsOnSingleTap());
    }

    @Test
    public void unknownModeFailsClosedToGesturesOn() {
        // An unknown stored label reads as the default mode, which is Single tap.
        PanelMediaMode unknown = StoredValues.fromStored(PanelMediaMode.class, "bogus");
        assertSame(PanelMediaMode.SINGLE_TAP, unknown);
        assertTrue(unknown.gesturesEnabled());
        assertTrue(unknown.revealsOnSingleTap());
    }
}
