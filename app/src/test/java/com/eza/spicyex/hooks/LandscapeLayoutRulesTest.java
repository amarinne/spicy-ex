package com.eza.spicyex.hooks;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LandscapeLayoutRulesTest {
    @Test
    public void twoColumnNeedsAdaptiveWideLandscape() {
        assertTrue(NativeSpicyShellViewImpl.twoColumnEngaged(true, 2.0f, true));
        assertTrue(NativeSpicyShellViewImpl.twoColumnEngaged(true, 1.2f, true));
        assertFalse(NativeSpicyShellViewImpl.twoColumnEngaged(true, 1.19f, true));
        assertFalse(NativeSpicyShellViewImpl.twoColumnEngaged(false, 2.0f, true));
        assertFalse(NativeSpicyShellViewImpl.twoColumnEngaged(true, 2.0f, false));
        assertFalse(NativeSpicyShellViewImpl.twoColumnEngaged(false, 1.0f, false));
    }
}
