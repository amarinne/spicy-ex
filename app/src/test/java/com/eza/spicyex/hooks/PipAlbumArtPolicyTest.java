package com.eza.spicyex.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.eza.spicyex.Settings;
import com.eza.spicyex.settings.PanelPolicy;
import com.eza.spicyex.settings.PanelSnapshot;
import com.eza.spicyex.settings.SettingsUiSchema;

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
    public void schemaListsAlbumArtInThePipSection() {
        assertTrue(SettingsUiSchema.orderedSettings().contains(Settings.PIP_ALBUM_ART));
        assertTrue(SettingsUiSchema.orderedSettings(Settings.PIP).contains(Settings.PIP_ALBUM_ART));
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
        assertEquals("Top", TrackInfoReadoutController.pipEffectiveMode(true, true));
        // Off: every shape is lyrics-only.
        assertFalse(TrackInfoReadoutController.pipColumnAllowed(true, false));
        assertFalse(TrackInfoReadoutController.pipColumnAllowed(false, true));
        assertFalse(TrackInfoReadoutController.pipColumnAllowed(false, false));
        assertEquals("Off", TrackInfoReadoutController.pipEffectiveMode(true, false));
        assertEquals("Top", TrackInfoReadoutController.pipEffectiveMode(false, true));
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
        PanelSnapshot off = PanelSnapshot.builder().build();
        assertFalse(PanelPolicy.shouldRender(Settings.PIP_ALBUM_ART, off));
        PanelSnapshot on = PanelSnapshot.builder().put(Settings.PIP_ENABLED, true).build();
        assertTrue(PanelPolicy.shouldRender(Settings.PIP_ALBUM_ART, on));
    }
}
