package com.eza.spicyex.hooks;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LyricsLayoutEditorRuntimePolicyTest {
    @Test
    public void editorSuppressesChromeAutoHideRegardlessOfTimeout() {
        assertFalse(LyricsLayoutEditorRuntimePolicy.chromeAutoHideAllowed(true, 1));
        assertFalse(LyricsLayoutEditorRuntimePolicy.chromeAutoHideAllowed(true, 30));
        assertFalse(LyricsLayoutEditorRuntimePolicy.chromeAutoHideAllowed(false, 0));
        assertTrue(LyricsLayoutEditorRuntimePolicy.chromeAutoHideAllowed(false, 1));
    }

    @Test
    public void editorPinsChipWhileRememberingRequestedVisibility() {
        assertTrue(LyricsLayoutEditorRuntimePolicy.chipShouldBeVisible(false, true));
        assertTrue(LyricsLayoutEditorRuntimePolicy.chipShouldBeVisible(true, true));
        assertTrue(LyricsLayoutEditorRuntimePolicy.chipShouldBeVisible(true, false));
        assertFalse(LyricsLayoutEditorRuntimePolicy.chipShouldBeVisible(false, false));
    }
}
