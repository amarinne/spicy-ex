package com.eza.spicyex.ambient;

import java.util.Locale;

/**
 * The resolved shader used by the API 33+ ambient layer. Kawarp is the default.
 *
 * <p>Every entry except {@link #KAWARP} is a Paper shader. {@link #MESH_GRADIENT} keeps the
 * locally adapted Mesh Gradient and its short {@code mesh} command name; it also accepts
 * {@code mesh-gradient}. All other entries map one-to-one to a {@link PaperShaderCatalog} id and
 * use that id as their stable command-channel name.
 */
public enum AmbientShaderEffect {
    KAWARP("kawarp"),
    MESH_GRADIENT("mesh", "mesh-gradient"),
    SIMPLEX_NOISE("simplex-noise"),
    DOT_ORBIT("dot-orbit"),
    GOD_RAYS("god-rays"),
    VORONOI("voronoi"),
    WAVES("waves"),
    SPIRAL("spiral"),
    SWIRL("swirl"),
    NEURO_NOISE("neuro-noise"),
    STATIC_MESH_GRADIENT("static-mesh-gradient"),
    DOT_GRID("dot-grid"),
    STATIC_RADIAL_GRADIENT("static-radial-gradient"),
    PERLIN_NOISE("perlin-noise"),
    COLOR_PANELS("color-panels"),
    DITHERING("dithering"),
    IMAGE_DITHERING("image-dithering"),
    HALFTONE_DOTS("halftone-dots"),
    HALFTONE_CMYK("halftone-cmyk"),
    PAPER_TEXTURE("paper-texture"),
    WATER("water"),
    FLUTED_GLASS("fluted-glass"),
    HEATMAP("heatmap"),
    GEM_SMOKE("gem-smoke"),
    LIQUID_METAL("liquid-metal"),
    SMOKE_RING("smoke-ring"),
    WARP("warp"),
    METABALLS("metaballs"),
    PULSING_BORDER("pulsing-border"),
    GRAIN_GRADIENT("grain-gradient");

    /** The production default; also the safe effect when a candidate cannot be constructed. */
    public static final AmbientShaderEffect DEFAULT = KAWARP;

    private final String argument;
    private final String[] aliases;

    AmbientShaderEffect(String argument, String... aliases) {
        this.argument = argument;
        this.aliases = aliases;
    }

    /** The stable command-channel name; never a user-facing label. */
    public String argument() {
        return argument;
    }

    /** Whether this effect is backed by the imported Paper shader catalog. */
    public boolean isPaper() {
        return this != KAWARP && this != MESH_GRADIENT;
    }

    /** Kawarp needs the processed texture; Paper image shaders need the cover bitmap. */
    public boolean requiresArtworkTexture() {
        if (this == KAWARP) return true;
        if (!isPaper()) return false;
        PaperShaderCatalog.Spec spec = PaperShaderCatalog.get(argument);
        return spec != null && spec.usesImageTexture;
    }

    /**
     * The page gradient also uses cover colors when no texture layer is enabled, and every Paper
     * effect replaces its palette with the cover colors whether or not a texture is bound.
     */
    public boolean usesArtworkPalette(boolean textureEnabled) {
        if (this == KAWARP) return !textureEnabled;
        return true;
    }

    /** The effect a failed candidate falls back to. */
    public static AmbientShaderEffect fallback() {
        return DEFAULT;
    }

    /** @return the matching effect, or null when the argument is absent or unknown. */
    public static AmbientShaderEffect fromArgument(String raw) {
        if (raw == null) return null;
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) return null;
        for (AmbientShaderEffect effect : values()) {
            if (effect.argument.equals(value)) return effect;
            for (String alias : effect.aliases) {
                if (alias.equals(value)) return effect;
            }
        }
        return null;
    }
}
