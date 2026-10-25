package com.eza.spicyex.ambient;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Small renderer-owned presentation adjustments for full-screen lyric backgrounds.
 *
 * <p>The catalog keeps the pinned upstream defaults for provenance. These values are applied only
 * at bind time because several upstream defaults are intended for a small showcase tile, where
 * hard edges and zero softness read well but become distracting behind moving lyrics.</p>
 */
final class PaperShaderTuning {
    private static final Map<String, Map<String, Float>> OVERRIDES;

    static {
        Map<String, Map<String, Float>> effects = new HashMap<>();
        // Behind moving lyrics, cell, band, and blob edges should melt into each other.
        effects.put("simplex-noise", values("u_softness", 0.85f));
        effects.put("waves", values("u_softness", 0.12f));
        effects.put("swirl", values("u_softness", 0.80f));
        effects.put("perlin-noise", values("u_softness", 0.85f, "u_scale", 3.0f));
        effects.put("color-panels", values("u_blur", 0.40f));
        effects.put("fluted-glass", values("u_blur", 0.16f));
        effects.put("pulsing-border", values("u_softness", 0.82f));
        effects.put("grain-gradient", values("u_softness", 1.00f));
        effects.put("voronoi", values("u_gap", 0f, "u_glow", 0.70f, "u_scale", 1.40f,
                "u_stepsPerColor", 8f));
        effects.put("dot-orbit", values("u_stepsPerColor", 12f, "u_scale", 1.6f));
        effects.put("neuro-noise", values("u_scale", 1.6f));
        effects.put("god-rays", values("u_intensity", 1.00f, "u_density", 0.18f, "u_spotty", 0.10f,
                "u_bloom", 0.70f, "u_midSize", 0.45f, "u_midIntensity", 0.55f));
        OVERRIDES = Collections.unmodifiableMap(effects);
    }

    private PaperShaderTuning() {
    }

    static float[] value(String effectId, String uniform, float[] imported) {
        Map<String, Float> effect = OVERRIDES.get(effectId);
        Float override = effect == null ? null : effect.get(uniform);
        if (override == null || imported.length != 1) return imported;
        return new float[]{override};
    }

    static Float override(String effectId, String uniform) {
        Map<String, Float> effect = OVERRIDES.get(effectId);
        return effect == null ? null : effect.get(uniform);
    }

    static float[] valueForImage(String effectId, String uniform, float[] imported,
                                 boolean usesImageTexture) {
        if (usesImageTexture && "u_fit".equals(uniform)) return new float[]{2f};
        return value(effectId, uniform, imported);
    }

    /** Alternating uniform names and values. */
    private static Map<String, Float> values(Object... pairs) {
        Map<String, Float> result = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], (Float) pairs[i + 1]);
        return Collections.unmodifiableMap(result);
    }
}
