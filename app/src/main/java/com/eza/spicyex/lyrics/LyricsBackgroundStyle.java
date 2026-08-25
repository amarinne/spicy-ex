package com.eza.spicyex.lyrics;

import com.eza.spicyex.Settings;
import com.eza.spicyex.SpotifyPlusConfig;

/** Stored background modes plus one-time compatibility with the removed boolean toggle. */
public final class LyricsBackgroundStyle {
    public static final String GRADIENT = "Gradient";
    public static final String STATIC_TEXTURE = "Static texture";
    public static final String ANIMATED_TEXTURE = "Animated texture";

    private LyricsBackgroundStyle() {
    }

    public static String read(SpotifyPlusConfig config) {
        if (config == null) return GRADIENT;
        if (config.contains(Settings.BACKGROUND_STYLE)) {
            return normalize(config.get(Settings.BACKGROUND_STYLE));
        }
        return config.get(Settings.ENABLE_BACKGROUND) ? ANIMATED_TEXTURE : GRADIENT;
    }

    public static String normalize(String value) {
        if (STATIC_TEXTURE.equals(value)) return STATIC_TEXTURE;
        if (ANIMATED_TEXTURE.equals(value)) return ANIMATED_TEXTURE;
        return GRADIENT;
    }

    public static boolean usesTexture(String value) {
        return !GRADIENT.equals(normalize(value));
    }

    public static boolean isAnimated(String value) {
        return ANIMATED_TEXTURE.equals(normalize(value));
    }
}
