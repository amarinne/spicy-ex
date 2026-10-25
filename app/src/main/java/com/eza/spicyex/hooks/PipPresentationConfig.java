package com.eza.spicyex.hooks;

import com.eza.spicyex.Settings;
import com.eza.spicyex.settings.SettingsReader;

/**
 * Immutable picture-in-picture presentation snapshot: the six PiP settings read together.
 *
 * <p>Owns no schema, default, persistence, cache identity, or live observation. Consumers keep
 * taking a fresh snapshot when they mount or refresh, exactly where they read the settings before.
 */
final class PipPresentationConfig {
    final boolean enabled;
    final String shape;
    final String focus;
    final boolean controls;
    final boolean leaveSpotify;
    final boolean albumArt;

    private PipPresentationConfig(boolean enabled, String shape, String focus, boolean controls,
            boolean leaveSpotify, boolean albumArt) {
        this.enabled = enabled;
        this.shape = shape;
        this.focus = focus;
        this.controls = controls;
        this.leaveSpotify = leaveSpotify;
        this.albumArt = albumArt;
    }

    public static PipPresentationConfig read(SettingsReader reader) {
        return new PipPresentationConfig(
                Boolean.TRUE.equals(reader.get(Settings.PIP_ENABLED)),
                reader.get(Settings.PIP_SHAPE),
                reader.get(Settings.PIP_FOCUS),
                Boolean.TRUE.equals(reader.get(Settings.PIP_CONTROLS)),
                !Boolean.FALSE.equals(reader.get(Settings.PIP_LEAVE_SPOTIFY)),
                Boolean.TRUE.equals(reader.get(Settings.PIP_ALBUM_ART)));
    }

    /** The window shape's width:height, through the existing ratio helper. */
    int[] shapeRatio() {
        return Settings.pipShapeRatio(shape);
    }
}
