package com.eza.spicyex.lyrics;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PanelMediaModeTest {
    @Test
    public void offDisablesAllGestures() {
        assertFalse(PanelMediaMode.gesturesEnabled(PanelMediaMode.OFF));
        assertFalse(PanelMediaMode.revealOnSingleTap(PanelMediaMode.OFF));
    }

    @Test
    public void singleTapRevealsOverlay() {
        assertTrue(PanelMediaMode.gesturesEnabled(PanelMediaMode.SINGLE_TAP));
        assertTrue(PanelMediaMode.revealOnSingleTap(PanelMediaMode.SINGLE_TAP));
    }

    @Test
    public void doubleTapSkipsOverlayButKeepsGestures() {
        assertTrue(PanelMediaMode.gesturesEnabled(PanelMediaMode.DOUBLE_TAP));
        assertFalse(PanelMediaMode.revealOnSingleTap(PanelMediaMode.DOUBLE_TAP));
    }

    @Test
    public void unknownModeFailsClosedToGesturesOn() {
        assertTrue(PanelMediaMode.gesturesEnabled("bogus"));
        assertFalse(PanelMediaMode.revealOnSingleTap("bogus"));
    }
}
