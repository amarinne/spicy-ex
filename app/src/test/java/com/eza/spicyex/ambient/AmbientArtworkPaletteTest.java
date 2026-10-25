package com.eza.spicyex.ambient;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * JVM coverage for the cover-to-palette maths. The extractor is deliberately Android-free, so
 * these assertions run the same code the controller executes on its artwork worker.
 */
public class AmbientArtworkPaletteTest {
    private static int[] blocks(int left, int right, int leftColumns) {
        int[] pixels = new int[32 * 32];
        for (int y = 0; y < 32; y++) {
            for (int x = 0; x < 32; x++) {
                pixels[y * 32 + x] = x < leftColumns ? left : right;
            }
        }
        return pixels;
    }

    private static int[] uniform(int color) {
        int[] pixels = new int[32 * 32];
        java.util.Arrays.fill(pixels, color);
        return pixels;
    }

    private static float hue(int color) {
        return AmbientArtworkPalette.toHsv(color)[0];
    }

    private static float saturation(int color) {
        return AmbientArtworkPalette.toHsv(color)[1];
    }

    private static float value(int color) {
        return AmbientArtworkPalette.toHsv(color)[2];
    }

    private static float hueDistance(float first, float second) {
        float delta = Math.abs(first - second);
        return Math.min(delta, 360f - delta);
    }

    private static int channelSpread(int color) {
        int red = (color >> 16) & 0xff;
        int green = (color >> 8) & 0xff;
        int blue = color & 0xff;
        return Math.max(red, Math.max(green, blue)) - Math.min(red, Math.min(green, blue));
    }

    @Test
    public void dominantHuesFollowTheArtwork() {
        int red = 0xffc02020;
        int blue = 0xff2040c0;
        int[] palette = AmbientArtworkPalette.extract(blocks(red, blue, 19), 4);
        assertNotNull(palette);
        assertEquals(2, palette.length);
        // The larger red field leads; the second field is the other actual artwork hue.
        assertTrue(hueDistance(hue(palette[0]), hue(red)) < 10f);
        assertTrue(hueDistance(hue(palette[1]), hue(blue)) < 10f);
    }

    @Test
    public void mutedArtworkKeepsItsSaturationAndValue() {
        int olive = 0xff6b7040;
        int[] palette = AmbientArtworkPalette.extract(uniform(olive), 4);
        assertNotNull(palette);
        assertEquals(2, palette.length);
        assertEquals(olive, palette[0]);
        assertEquals(olive, palette[1]);
        // The previous extractor forced saturation up and brightness to at least 0.5, so muted
        // and dark covers read as colours the artwork does not contain.
        assertEquals(saturation(olive), saturation(palette[0]), 0.005f);
        assertEquals(value(olive), value(palette[0]), 0.005f);
    }

    @Test
    public void darkArtworkIsNotForcedBright() {
        int navy = 0xff1a1a2e;
        int[] palette = AmbientArtworkPalette.extract(uniform(navy), 4);
        assertNotNull(palette);
        assertEquals(navy, palette[0]);
        assertTrue(value(palette[0]) < 0.25f);
        assertTrue(hueDistance(hue(palette[0]), 240f) < 10f);
    }

    @Test
    public void neutralArtworkKeepsLightAndDarkContrast() {
        int[] palette = AmbientArtworkPalette.extract(blocks(0xff202020, 0xffe0e0e0, 16), 4);
        assertNotNull(palette);
        assertEquals(2, palette.length);
        for (int color : palette) {
            assertTrue("neutral field: " + Integer.toHexString(color), channelSpread(color) <= 2);
        }
        assertTrue(value(palette[0]) < 0.2f);
        assertTrue(value(palette[1]) > 0.8f);
    }

    @Test
    public void uniformNeutralArtworkStaysNeutral() {
        int gray = 0xff808080;
        int[] palette = AmbientArtworkPalette.extract(uniform(gray), 4);
        assertNotNull(palette);
        assertEquals(2, palette.length);
        assertEquals(gray, palette[0]);
        assertEquals(gray, palette[1]);
    }

    @Test
    public void transparentOrEmptyPixelsHaveNoPalette() {
        assertNull(AmbientArtworkPalette.extract(null, 4));
        assertNull(AmbientArtworkPalette.extract(new int[0], 4));
        assertNull(AmbientArtworkPalette.extract(new int[]{0x00ff0000, 0x00000000}, 4));
        assertNull(AmbientArtworkPalette.extract(uniform(0xffff0000), 0));
    }

    @Test
    public void uniformColourPadsToTwoFieldsForTheRenderer() {
        int red = 0xffff0000;
        int[] palette = AmbientArtworkPalette.extract(uniform(red), 4);
        assertNotNull(palette);
        assertArrayEquals(new int[]{red, red}, palette);
    }

    @Test
    public void neutralFallbackDoesNotInventHue() {
        int[] colors = AmbientArtworkPalette.fallback(0xff9a9a9a, true);
        assertEquals(3, colors.length);
        for (int color : colors) {
            assertTrue("invented tint: " + Integer.toHexString(color), channelSpread(color) <= 2);
        }
        assertTrue("light/dark separation", value(colors[0]) > value(colors[1]));
    }

    @Test
    public void fallbackKeepsTheSeedHue() {
        int seed = 0xffc02020;
        int[] colors = AmbientArtworkPalette.fallback(seed, true);
        assertTrue(hueDistance(hue(colors[0]), hue(seed)) < 5f);
        assertTrue(hueDistance(hue(colors[1]), hue(seed)) < 5f);
        assertTrue("separation by value, not hue", value(colors[0]) > value(colors[1]));
        // The old separation shifted the bright stop by 28 degrees.
        assertTrue(hueDistance(hue(colors[0]), 28f) > 10f);
    }

    @Test
    public void baseColourKeepsTheLeadHue() {
        int red = 0xffc02020;
        int base = AmbientArtworkPalette.baseColor(red);
        assertTrue(hueDistance(hue(base), hue(red)) < 5f);
        assertEquals(0.24f, value(base), 0.01f);
    }
}
