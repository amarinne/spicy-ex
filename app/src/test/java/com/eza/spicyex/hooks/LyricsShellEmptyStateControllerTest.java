package com.eza.spicyex.hooks;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class LyricsShellEmptyStateControllerTest {
    @Test
    public void loadingStartsAtViewportMiddleAcrossOrientations() {
        assertEquals(56, LyricsShellEmptyStateController.loadingTopMargin(1200, 544));
        assertEquals(56, LyricsShellEmptyStateController.loadingTopMargin(600, 244));
    }

    @Test
    public void loadingRespectsPaddingPastViewportMiddle() {
        assertEquals(0, LyricsShellEmptyStateController.loadingTopMargin(600, 340));
    }

    @Test
    public void loadingWaitsForARealViewport() {
        assertEquals(0, LyricsShellEmptyStateController.loadingTopMargin(0, 100));
    }
}
