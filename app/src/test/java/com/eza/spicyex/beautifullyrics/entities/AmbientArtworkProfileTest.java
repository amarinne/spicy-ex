package com.eza.spicyex.beautifullyrics.entities;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Deterministic JVM tests for artwork variance profiling and adaptive parameter selection.
 * Full AGSL rendering is device-specific, so shader behavior is covered here through the
 * pure parameter-selection contract (bounded modulation / warp / cycle).
 */
public class AmbientArtworkProfileTest {
    private static int[] uniform(int color, int n) {
        int[] pixels = new int[n];
        java.util.Arrays.fill(pixels, color);
        return pixels;
    }

    private static int[] halfHalf(int left, int right, int w, int h) {
        int[] pixels = new int[w * h];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            pixels[y * w + x] = x < w / 2 ? left : right;
        }
        return pixels;
    }

    @Test public void uniformSingleColorIsNearUniform() {
        AmbientArtworkProfile profile = AmbientArtworkProfile.fromPixels(uniform(0xff3a7bd5, 64 * 64));
        assertEquals(AmbientArtworkProfile.Detail.NEAR_UNIFORM, profile.detail());
        assertTrue(profile.isFlat());
        assertTrue(profile.isNearUniform());
        assertEquals(0f, profile.luminanceVariance, 1e-6f);
        assertEquals(0f, profile.hueSpread, 1e-6f);
        assertEquals(0xff3a7bd5, profile.averageColor);
    }

    @Test public void grayscaleUniformIsNearUniformWithZeroHueSpread() {
        AmbientArtworkProfile profile = AmbientArtworkProfile.fromPixels(uniform(0xff808080, 32 * 32));
        assertEquals(AmbientArtworkProfile.Detail.NEAR_UNIFORM, profile.detail());
        assertEquals(0f, profile.hueSpread, 1e-6f);
        assertEquals(0f, profile.averageSaturation, 1e-6f);
    }

    @Test public void darkAndBrightUniformsShareClassButNotAverage() {
        AmbientArtworkProfile dark = AmbientArtworkProfile.fromPixels(uniform(0xff0a0a0c, 16 * 16));
        AmbientArtworkProfile bright = AmbientArtworkProfile.fromPixels(uniform(0xfff5f0e6, 16 * 16));
        assertEquals(AmbientArtworkProfile.Detail.NEAR_UNIFORM, dark.detail());
        assertEquals(AmbientArtworkProfile.Detail.NEAR_UNIFORM, bright.detail());
        assertNotEquals(dark.averageColor, bright.averageColor);
        assertEquals(0xff0a0a0c, dark.averageColor);
        assertEquals(0xfff5f0e6, bright.averageColor);
    }

    @Test public void highContrastSplitIsDetailed() {
        AmbientArtworkProfile profile =
                AmbientArtworkProfile.fromPixels(halfHalf(0xff000000, 0xffffffff, 64, 64));
        assertEquals(AmbientArtworkProfile.Detail.DETAILED, profile.detail());
        assertFalse(profile.isFlat());
        assertFalse(profile.isNearUniform());
        assertTrue(profile.luminanceVariance > 0.05f);
    }

    @Test public void colorfulCheckerHasHueSpreadButGrayscaleSplitDoesNot() {
        int w = 32, h = 32;
        int[] colorful = new int[w * h];
        int[] gray = new int[w * h];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            boolean even = ((x / 4) + (y / 4)) % 2 == 0;
            colorful[y * w + x] = even ? 0xffff0000 : 0xff0000ff;
            gray[y * w + x] = even ? 0xff000000 : 0xffffffff;
        }
        AmbientArtworkProfile colorProfile = AmbientArtworkProfile.fromPixels(colorful);
        AmbientArtworkProfile grayProfile = AmbientArtworkProfile.fromPixels(gray);
        assertTrue(colorProfile.hueSpread > 0.4f);
        assertEquals(0f, grayProfile.hueSpread, 1e-6f);
        assertTrue(colorProfile.luminanceVariance < grayProfile.luminanceVariance);
    }

    @Test public void detailedAndFlatProfilesAreDistinct() {
        AmbientArtworkProfile flat = AmbientArtworkProfile.fromPixels(uniform(0xff445566, 64 * 64));
        AmbientArtworkProfile detailed =
                AmbientArtworkProfile.fromPixels(halfHalf(0xff112233, 0xffddee00, 64, 64));
        assertNotEquals(flat.detail(), detailed.detail());
        assertTrue(detailed.luminanceVariance > flat.luminanceVariance);
        assertNotEquals(flat.rendererParams().modulation,
                detailed.rendererParams().modulation, 1e-6f);
    }

    @Test public void identicalInputsGiveStableProfiles() {
        int[] pixels = halfHalf(0xff123456, 0xffabcdef, 48, 48);
        // Sprinkle deterministic hue variation so both luminance and hue paths are exercised.
        for (int i = 0; i < pixels.length; i += 7) pixels[i] = 0xffff8800;
        AmbientArtworkProfile first = AmbientArtworkProfile.fromPixels(pixels);
        AmbientArtworkProfile second = AmbientArtworkProfile.fromPixels(pixels.clone());
        assertEquals(first.averageColor, second.averageColor);
        assertEquals(first.luminanceVariance, second.luminanceVariance, 0f);
        assertEquals(first.hueSpread, second.hueSpread, 0f);
        assertEquals(first.averageSaturation, second.averageSaturation, 0f);
        assertEquals(first.detail(), second.detail());
    }

    @Test public void profilingDoesNotMutateInputOrRetainIt() {
        int[] pixels = halfHalf(0xff101010, 0xffe0e0e0, 24, 24);
        int[] snapshot = pixels.clone();
        AmbientArtworkProfile profile = AmbientArtworkProfile.fromPixels(pixels);
        assertArrayEquals(snapshot, pixels);
        int avgBefore = profile.averageColor;
        float lumBefore = profile.luminanceVariance;
        // Mutating the caller array afterwards must not change the already-returned profile:
        // the profile is a few floats, not a live view over the pixels.
        java.util.Arrays.fill(pixels, 0xffff0000);
        assertEquals(avgBefore, profile.averageColor);
        assertEquals(lumBefore, profile.luminanceVariance, 0f);
    }

    @Test public void modulationBoundsMatchMobileTuning() {
        AmbientArtworkProfile.RendererParams detailed =
                AmbientArtworkProfile.paramsFor(AmbientArtworkProfile.Detail.DETAILED);
        AmbientArtworkProfile.RendererParams low =
                AmbientArtworkProfile.paramsFor(AmbientArtworkProfile.Detail.LOW_VARIANCE);
        AmbientArtworkProfile.RendererParams uniform =
                AmbientArtworkProfile.paramsFor(AmbientArtworkProfile.Detail.NEAR_UNIFORM);
        // Detailed 4-8%, flat 10-18%, near-uniform up to 20%.
        assertTrue(detailed.modulation >= 0.04f && detailed.modulation <= 0.08f);
        assertTrue(low.modulation >= 0.10f && low.modulation <= 0.18f);
        assertTrue(uniform.modulation > low.modulation && uniform.modulation <= 0.20f);
        // Global bound: never outside 4-20%.
        for (AmbientArtworkProfile.RendererParams p : new AmbientArtworkProfile.RendererParams[]{
                detailed, low, uniform}) {
            assertTrue(p.modulation >= 0.04f && p.modulation <= 0.20f);
            // Warp stays below the 0.10 shader inset (no edge clamping plateaus).
            assertTrue(p.warp >= 0.010f && p.warp <= 0.060f);
            // Cycle stays in the 8-14 s slow-drift band.
            assertTrue(p.cycleSeconds >= 8f && p.cycleSeconds <= 14f);
            assertTrue(p.hue >= 0f && p.hue <= 1f);
        }
        // Low-variance gets the broadest movement; near-uniform the most restrained warp.
        assertTrue(low.warp > detailed.warp);
        assertTrue(uniform.warp < detailed.warp);
    }

    @Test public void classificationThresholdsAreMonotone() {
        assertEquals(AmbientArtworkProfile.Detail.NEAR_UNIFORM,
                AmbientArtworkProfile.classify(0f, 0f));
        assertEquals(AmbientArtworkProfile.Detail.LOW_VARIANCE,
                AmbientArtworkProfile.classify(0.001f, 0.01f));
        assertEquals(AmbientArtworkProfile.Detail.LOW_VARIANCE,
                AmbientArtworkProfile.classify(0.010f, 0.01f));
        assertEquals(AmbientArtworkProfile.Detail.DETAILED,
                AmbientArtworkProfile.classify(0.010f, 0.50f));
    }

    @Test public void nullAndEmptyInputsAreSafeNearUniform() {
        assertEquals(AmbientArtworkProfile.Detail.NEAR_UNIFORM,
                AmbientArtworkProfile.fromPixels(null).detail());
        assertEquals(AmbientArtworkProfile.Detail.NEAR_UNIFORM,
                AmbientArtworkProfile.fromPixels(new int[0]).detail());
    }

    @Test public void averageColorIsExactForSplitInput() {
        // 0x10/0x20: average must round-half-up per channel, alpha always opaque.
        AmbientArtworkProfile profile =
                AmbientArtworkProfile.fromPixels(halfHalf(0xff101010, 0xff202020, 8, 8));
        assertEquals(0xff181818, profile.averageColor);
    }
}
