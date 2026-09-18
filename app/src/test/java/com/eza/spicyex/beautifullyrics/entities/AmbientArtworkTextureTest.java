package com.eza.spicyex.beautifullyrics.entities;

import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;

public class AmbientArtworkTextureTest {
    @Test public void constantColorsSurviveEveryPassWithoutDarkening() {
        int[] pixels = new int[35];
        Arrays.fill(pixels, 0xff192b3d);
        float[] result = AmbientArtworkTexture.kawase(pixels, 7, 5, false);
        for (int i = 0; i < pixels.length; i++) {
            assertEquals(25 / 255f, result[3*i], 0.000001f);
            assertEquals(43 / 255f, result[3*i+1], 0.000001f);
            assertEquals(61 / 255f, result[3*i+2], 0.000001f);
            assertEquals(0xff192b3d, pixels[i]);
        }
    }

    @Test public void darkTintMatchesDesktopAndDoesNotTintWhite() {
        float[] black = AmbientArtworkTexture.kawase(new int[]{0xff000000}, 1, 1, true);
        assertArrayEquals(new float[]{0.025f*0.38f, 0.022f*0.38f, 0.03f*0.38f}, black, 0.000001f);
        float[] white = AmbientArtworkTexture.kawase(new int[]{0xffffffff}, 1, 1, true);
        assertArrayEquals(new float[]{1f, 1f, 1f}, white, 0.000001f);
    }

    @Test public void kawaseImpulseMatchesDesktopKernelVarianceAndRetainsSubByteSteps() {
        int w = 83, h = 81;
        int[] pixels = new int[w*h];
        Arrays.fill(pixels, 0xff000000);
        pixels[(h/2)*w+w/2] = 0xffffffff;
        float[] result = AmbientArtworkTexture.kawase(pixels, w, h, false);
        double sum = 0, variance = 0;
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            float value = result[3*(y*w+x)];
            assertEquals(value, result[3*(y*w+w-1-x)], 0.00000001f);
            assertEquals(value, result[3*((h-1-y)*w+x)], 0.00000001f);
            sum += value;
            variance += value * (x-w/2) * (x-w/2);
        }
        assertEquals(1.0, sum, 0.00001);
        // The complete kernel fits inside this fixture, so clamped edges cannot lose energy.
        // Each bilinear half-texel corner contributes offset^2 + .25 per axis.
        assertEquals(172.0, variance, 0.00001);
        float center = result[3*((h/2)*w+w/2)];
        assertTrue(center > 0f && center < 0.5f/255f);
    }
}
