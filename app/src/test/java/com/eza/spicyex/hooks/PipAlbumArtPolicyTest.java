package com.eza.spicyex.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.eza.spicyex.Settings;
import com.eza.spicyex.settings.registry.ControlRegistry;
import com.eza.spicyex.settings.registry.ControlSpec;
import com.eza.spicyex.settings.registry.SettingsSnapshot;
import com.eza.spicyex.settings.values.TrackInfoPosition;
import com.eza.spicyex.settings.registry.UiContext;

import org.junit.Test;

/** PiP album-art toggle: off-default, panel wiring, and the two-column geometry gate. */
public class PipAlbumArtPolicyTest {
    @Test
    public void albumArtIsOffByDefaultInThePipSection() {
        assertEquals("lyrics_pip_album_art", Settings.PIP_ALBUM_ART.key);
        assertEquals(Settings.PIP, Settings.PIP_ALBUM_ART.section);
        assertEquals("Show album art", Settings.PIP_ALBUM_ART.label);
        assertFalse(Settings.PIP_ALBUM_ART.defaultValue);
    }

    @Test
    public void theRegistryListsAlbumArtInThePipSection() {
        boolean listed = false;
        for (ControlSpec entry : ControlRegistry.panelControls(Settings.PIP)) {
            if (entry.setting == Settings.PIP_ALBUM_ART) listed = true;
        }
        assertTrue(listed);
    }

    @Test
    public void pipTwoColumnNeedsLandscapeAdaptiveAndAlbumArt() {
        assertTrue(NativeSpicyShellViewImpl.pipTwoColumnEngaged(
                NativeSpicyShellViewImpl.PIP_LAYOUT_LANDSCAPE, true, true));
        // Toggle off: lyrics-only, no metadata and no blank column.
        assertFalse(NativeSpicyShellViewImpl.pipTwoColumnEngaged(
                NativeSpicyShellViewImpl.PIP_LAYOUT_LANDSCAPE, true, false));
        // Adaptive off still wins, even with art on.
        assertFalse(NativeSpicyShellViewImpl.pipTwoColumnEngaged(
                NativeSpicyShellViewImpl.PIP_LAYOUT_LANDSCAPE, false, true));
        // Portrait and fullscreen shells never take the PiP column path.
        assertFalse(NativeSpicyShellViewImpl.pipTwoColumnEngaged(
                NativeSpicyShellViewImpl.PIP_LAYOUT_PORTRAIT, true, true));
        assertFalse(NativeSpicyShellViewImpl.pipTwoColumnEngaged(
                NativeSpicyShellViewImpl.PIP_LAYOUT_NONE, true, true));
    }

    @Test
    public void pipColumnGateRestoresOnlyTheLandscapeColumn() {
        assertTrue(TrackInfoReadoutController.pipColumnAllowed(true, true));
        assertEquals(TrackInfoPosition.TOP, TrackInfoReadoutController.pipEffectiveMode(true, true));
        // Off: every shape is lyrics-only.
        assertFalse(TrackInfoReadoutController.pipColumnAllowed(false, true));
        assertFalse(TrackInfoReadoutController.pipColumnAllowed(true, false));
        assertEquals(TrackInfoPosition.OFF, TrackInfoReadoutController.pipEffectiveMode(true, false));
    }

    @Test
    public void fullscreenTwoColumnIgnoresThePipToggle() {
        // The fullscreen adaptive layout never consults the PiP art flag.
        assertTrue(NativeSpicyShellViewImpl.twoColumnEngaged(800f, 400f, true));
        assertFalse(NativeSpicyShellViewImpl.twoColumnEngaged(800f, 400f, false));
        assertFalse(NativeSpicyShellViewImpl.twoColumnEngaged(400f, 860f, true));
    }

    @Test
    public void albumArtRowFollowsThePipMasterSwitch() {
        assertFalse(visible(Settings.PIP_ALBUM_ART, context(false)));
        assertTrue(visible(Settings.PIP_ALBUM_ART, context(true)));
    }

    private static UiContext context(boolean pipEnabled) {
        java.util.Map<String, Object> values = new java.util.LinkedHashMap<>();
        values.put(Settings.PIP_ENABLED.key, pipEnabled);
        return new UiContext(false, false, false, new SettingsSnapshot(values), true);
    }

    private static boolean visible(Settings.Setting<?> setting, UiContext context) {
        for (ControlSpec entry : ControlRegistry.visiblePanelControls(context)) {
            if (entry.setting == setting) return true;
        }
        return false;
    }
}
