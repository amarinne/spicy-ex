package com.eza.spicyex.hooks;

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
}
