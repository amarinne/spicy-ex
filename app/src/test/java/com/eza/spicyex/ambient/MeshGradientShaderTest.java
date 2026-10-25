package com.eza.spicyex.ambient;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class MeshGradientShaderTest {
    @Test
    public void palettesKeepRgbAndMakeTranslucentInputsOpaque() {
        float[] packed = MeshGradientShader.packColors(new int[]{0x80123456, 0xffabcdef});
        assertEquals(0x12 / 255f, packed[0], 0.000001f);
        assertEquals(0x34 / 255f, packed[1], 0.000001f);
        assertEquals(0x56 / 255f, packed[2], 0.000001f);
        assertEquals(1f, packed[3], 0f);
        assertEquals(0xab / 255f, packed[4], 0.000001f);
        assertEquals(0xcd / 255f, packed[5], 0.000001f);
        assertEquals(0xef / 255f, packed[6], 0.000001f);
        assertEquals(1f, packed[7], 0.000001f);
    }

    @Test
    public void unusedPaletteSlotsStayZero() {
        float[] packed = MeshGradientShader.packColors(new int[]{0xffffffff, 0xff000000});
        assertEquals(1f, packed[7], 0.000001f);
        for (int i = 8; i < packed.length; i++) {
            assertEquals("slot " + i, 0f, packed[i], 0f);
        }
    }

    @Test
    public void thePaletteStopsAtPapersTenColours() {
        int[] colours = new int[MeshGradientShader.MAX_COLORS + 2];
        for (int i = 0; i < colours.length; i++) colours[i] = 0xffffffff;
        float[] packed = MeshGradientShader.packColors(colours);
        assertEquals(1f, packed[MeshGradientShader.MAX_COLORS * 4 - 1], 0.000001f);
        for (int i = MeshGradientShader.MAX_COLORS * 4; i < packed.length; i++) {
            assertEquals(0f, packed[i], 0f);
        }
    }

    @Test
    public void missingPalettePacksToTransparentBlack() {
        for (float value : MeshGradientShader.packColors(null)) assertEquals(0f, value, 0f);
        for (float value : MeshGradientShader.packColors(new int[0])) assertEquals(0f, value, 0f);
    }

    @Test
    public void theUpstreamGrainGuardsAreRestored() {
        // The Android port computes both grain paths unconditionally; the upstream source only
        // pays for them above zero, and this layer keeps grain disabled.
        assertTrue(MeshGradientShader.SOURCE.contains("if (u_grainMixer > 0.0)"));
        assertTrue(MeshGradientShader.SOURCE.contains("if (u_grainOverlay > 0.0)"));
    }

    @Test
    public void theMeshDoesNotAcquireUnrelatedInputs() {
        assertFalse(MeshGradientShader.SOURCE.contains("uniform shader"));
        assertFalse(MeshGradientShader.SOURCE.contains("u_image"));
        assertFalse(MeshGradientShader.SOURCE.contains("texture("));
        assertFalse(MeshGradientShader.SOURCE.contains("noiseTexture"));
        assertFalse(MeshGradientShader.SOURCE.contains("#version"));
        assertFalse(MeshGradientShader.SOURCE.contains("fwidth("));
        assertFalse(MeshGradientShader.SOURCE.contains("discard"));
    }

    @Test
    public void transparentPaletteEntriesAreOpaqueBackgroundColors() {
        float[] packed = MeshGradientShader.packColors(new int[]{0x00ff0000, 0x00000000});
        assertEquals(1f, packed[0], 0f);
        assertEquals(1f, packed[3], 0f);
        assertEquals(0f, packed[4], 0f);
        assertEquals(1f, packed[7], 0f);
    }

    @Test
    public void theFinalGradeStillRunsAfterTheMeshColour() {
        assertTrue(MeshGradientShader.SOURCE.contains("uniform float u_phase;"));
        assertTrue(MeshGradientShader.SOURCE.contains("uniform float u_motion;"));
        assertTrue(MeshGradientShader.SOURCE.contains("* u_motion"));
        assertTrue(MeshGradientShader.SOURCE.contains("float cssGray = dot(color"));
        assertTrue(MeshGradientShader.SOURCE.contains("mix(normal, dim, dark) * brightness"));
        assertTrue(MeshGradientShader.SOURCE.contains("brightness * 255.0"));
    }

    @Test
    public void meshAndKawarpResolveToDifferentSources() {
        assertEquals(MeshGradientShader.SOURCE,
                AmbientArtworkBackgroundView.shaderSource(AmbientShaderEffect.MESH_GRADIENT));
        String kawarp = AmbientArtworkBackgroundView.shaderSource(AmbientShaderEffect.KAWARP);
        assertNotEquals(MeshGradientShader.SOURCE, kawarp);
        assertTrue(kawarp.contains("snoise"));
        assertFalse(kawarp.contains("u_colors[10]"));
        assertEquals(kawarp, AmbientArtworkBackgroundView.shaderSource(null));
    }
}
