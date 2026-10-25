package com.eza.spicyex.ambient;

import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Palette packing is pure Java and must preserve the imported per-slot alpha contract. */
public class PaperShaderBindingTest {
    @Test
    public void paletteColorsCycleAndImportedAlphaIsRetained() {
        PaperShaderCatalog.Spec spec = PaperShaderCatalog.get("simplex-noise");
        assertNotNull(spec);
        int[] palette = {0x00112233, 0x00aabbcc};
        float[] colors = PaperShaderBinding.paletteUniforms(spec, palette).get("u_colors");
        assertNotNull(colors);
        assertEquals(40, colors.length);
        assertEquals(0x11 / 255f, colors[0], 1e-6f);
        assertEquals(0x22 / 255f, colors[1], 1e-6f);
        assertEquals(0x33 / 255f, colors[2], 1e-6f);
        assertEquals(0xaa / 255f, colors[4], 1e-6f);
        assertEquals(0xbb / 255f, colors[5], 1e-6f);
        assertEquals(0xcc / 255f, colors[6], 1e-6f);
        // The five imported colors are opaque; the five unused capacity slots stay transparent.
        for (int slot = 0; slot < 5; slot++) {
            assertEquals(1f, colors[slot * 4 + 3], 0f);
        }
        for (int slot = 5; slot < 10; slot++) {
            assertEquals(0f, colors[slot * 4 + 3], 0f);
        }
    }

    @Test
    public void singleColorDeclarationsAdvanceThePaletteCursor() {
        PaperShaderCatalog.Spec spec = PaperShaderCatalog.get("dot-orbit");
        assertNotNull(spec);
        int[] palette = {0x00112233, 0x00445566};
        Map<String, float[]> colors = PaperShaderBinding.paletteUniforms(spec, palette);
        float[] back = colors.get("u_colorBack");
        assertNotNull(back);
        assertEquals(4, back.length);
        assertEquals(0x11 / 255f, back[0], 1e-6f);
        assertEquals(1f, back[3], 0f);
        float[] dots = colors.get("u_colors");
        assertNotNull(dots);
        // u_colorBack consumed slot zero, so the dot array begins on the second palette entry.
        assertEquals(0x44 / 255f, dots[0], 1e-6f);
        assertEquals(0x11 / 255f, dots[4], 1e-6f);
    }

    @Test
    public void importedDefaultsSurviveAnEmptyPalette() {
        PaperShaderCatalog.Spec spec = PaperShaderCatalog.get("god-rays");
        assertNotNull(spec);
        Map<String, float[]> colors = PaperShaderBinding.paletteUniforms(spec, new int[0]);
        assertArrayEquals(new float[]{0f, 0f, 0f, 1f}, colors.get("u_colorBack"), 1e-6f);
    }

    @Test
    public void timeScalesByTheCatalogSpeed() {
        PaperShaderCatalog.Spec animated = PaperShaderCatalog.get("simplex-noise");
        PaperShaderCatalog.Spec staticSpec = PaperShaderCatalog.get("waves");
        assertNotNull(animated);
        assertNotNull(staticSpec);
        assertEquals(1.25f, PaperShaderBinding.paperTime(animated, 2.5), 1e-6f);
        assertEquals(0f, PaperShaderBinding.paperTime(staticSpec, 2.5), 0f);
    }

    @Test
    public void everyColorDeclarationHasAPackedDefault() {
        for (PaperShaderCatalog.Spec spec : PaperShaderCatalog.all()) {
            Map<String, float[]> colors = PaperShaderBinding.paletteUniforms(spec, new int[]{0});
            for (String name : spec.colorCapacities.keySet()) {
                float[] values = colors.get(name);
                assertNotNull(spec.id + " " + name, values);
                assertEquals((int) spec.colorCapacities.get(name) * 4, values.length);
            }
        }
    }

    @Test
    public void everyBoundColorComesFromThePalette() {
        int[] palette = {0xff102030, 0xff405060, 0xff708090, 0xffa0b0c0};
        for (PaperShaderCatalog.Spec spec : PaperShaderCatalog.all()) {
            Map<String, float[]> colors = PaperShaderBinding.paletteUniforms(spec, palette);
            for (Map.Entry<String, float[]> entry : colors.entrySet()) {
                float[] values = entry.getValue();
                for (int slot = 0; slot < values.length / 4; slot++) {
                    assertTrue(spec.id + " " + entry.getKey() + " slot " + slot
                                    + " kept a catalog demo colour",
                            matchesPalette(values, slot, palette));
                }
            }
        }
    }

    @Test
    public void paletteBindingLeavesTheImportedColorCountAlone() {
        for (PaperShaderCatalog.Spec spec : PaperShaderCatalog.all()) {
            if (!spec.uniforms.containsKey("u_colorsCount")) continue;
            Map<String, float[]> colors =
                    PaperShaderBinding.paletteUniforms(spec, new int[]{0xff112233});
            assertTrue(spec.id + " must not rewrite u_colorsCount from the palette",
                    !colors.containsKey("u_colorsCount"));
        }
    }

    private static boolean matchesPalette(float[] values, int slot, int[] palette) {
        for (int color : palette) {
            float red = ((color >> 16) & 0xff) / 255f;
            float green = ((color >> 8) & 0xff) / 255f;
            float blue = (color & 0xff) / 255f;
            if (Math.abs(values[slot * 4] - red) < 1e-5f
                    && Math.abs(values[slot * 4 + 1] - green) < 1e-5f
                    && Math.abs(values[slot * 4 + 2] - blue) < 1e-5f) {
                return true;
            }
        }
        return false;
    }
}
