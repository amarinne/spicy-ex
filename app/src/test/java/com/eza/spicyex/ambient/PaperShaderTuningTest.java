package com.eza.spicyex.ambient;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNull;

/** Presentation tuning stays bounded and only changes the intended scalar uniforms. */
public class PaperShaderTuningTest {
    @Test
    public void softenedEffectsUseModerateFullScreenValues() {
        assertEquals(0.85f, tuned("simplex-noise", "u_softness"), 0f);
        assertEquals(0.12f, tuned("waves", "u_softness"), 0f);
        assertEquals(0.40f, tuned("color-panels", "u_blur"), 0f);
        assertEquals(0.16f, tuned("fluted-glass", "u_blur"), 0f);
        assertEquals(1.00f, tuned("grain-gradient", "u_softness"), 0f);
    }

    @Test
    public void cellEffectsLoseHardGapsAndContours() {
        assertEquals(0f, tuned("voronoi", "u_gap"), 0f);
        assertEquals(0.70f, tuned("voronoi", "u_glow"), 0f);
        assertEquals(12f, tuned("dot-orbit", "u_stepsPerColor"), 0f);
        assertEquals(0.18f, tuned("god-rays", "u_density"), 0f);
        assertEquals(1.00f, tuned("god-rays", "u_intensity"), 0f);
    }

    @Test
    public void fixedEdgeEffectsGetWideRendererEdges() {
        String dots = AmbientArtworkBackgroundView.shaderSource(AmbientShaderEffect.DOT_ORBIT);
        assertTrue(dots.contains("max(ps_pixelDerivative(1.0), 0.8 * radius)"));
        assertFalse(dots.contains("float edgeWidth = ps_pixelDerivative(1.0);"));
        String rays = AmbientArtworkBackgroundView.shaderSource(AmbientShaderEffect.GOD_RAYS);
        assertTrue(rays.contains("vec2(fragCoord.x - 0.5 * u_resolution.x, -fragCoord.y)"));
        assertFalse(rays.contains("vec2 shapeUv = ps_objectUV(fragCoord);"));
        String blobs = AmbientArtworkBackgroundView.shaderSource(AmbientShaderEffect.METABALLS);
        assertTrue(blobs.contains("smoothstep(0.1, 0.75 + edgeWidth, totalShape)"));
        assertFalse(blobs.contains("smoothstep(0.4, 0.4 + edgeWidth, totalShape)"));
    }

    @Test
    public void nonTunedUniformsAndPackedColorsKeepImportedValues() {
        PaperShaderCatalog.Spec spec = PaperShaderCatalog.get("fluted-glass");
        float[] imported = spec.uniforms.get("u_edges");
        float[] result = PaperShaderTuning.value(spec.id, "u_edges", imported);
        assertEquals(imported[0], result[0], 0f);
        assertNull(PaperShaderTuning.override("fluted-glass", "u_colorBack"));
    }

    @Test
    public void imageEffectsCropArtworkToTheAmbientSurface() {
        assertEquals(2f, tunedImage("water", "u_fit"), 0f);
        assertEquals(2f, tunedImage("liquid-metal", "u_fit"), 0f);
        assertEquals(2f, tunedImage("image-dithering", "u_fit"), 0f);
    }

    private static float tuned(String effect, String uniform) {
        PaperShaderCatalog.Spec spec = PaperShaderCatalog.get(effect);
        float[] imported = spec.uniforms.get(uniform);
        return PaperShaderTuning.value(effect, uniform, imported)[0];
    }

    private static float tunedImage(String effect, String uniform) {
        PaperShaderCatalog.Spec spec = PaperShaderCatalog.get(effect);
        float[] imported = spec.uniforms.get(uniform);
        return PaperShaderTuning.valueForImage(effect, uniform, imported,
                spec.usesImageTexture)[0];
    }
}
