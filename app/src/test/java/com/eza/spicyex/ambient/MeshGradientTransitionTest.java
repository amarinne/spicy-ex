package com.eza.spicyex.ambient;

import org.junit.Test;
import static org.junit.Assert.*;

public class MeshGradientTransitionTest {
    @Test public void handoffInterpolatesPalettePhaseAndSpeedOverBoundedWindow() {
        float[] from = MeshGradientShader.packColors(new int[]{0xff000000, 0xffffffff});
        float[] to = MeshGradientShader.packColors(new int[]{0xffff0000, 0xff0000ff});
        MeshGradientTransition transition = new MeshGradientTransition(from, 2, 0f, 0.85f, 1L);
        assertTrue(transition.begin(2L, 1_000_000_000L, 2f, 1.25f, to, 2));
        transition.advance(1_000_000_000L + 325_000_000L);
        assertTrue(transition.active());
        assertTrue(transition.colors()[0] > from[0] && transition.colors()[0] < to[0]);
        assertTrue(transition.phase() > 0f && transition.phase() < 2f);
        assertTrue(transition.speed() > 0.85f && transition.speed() < 1.25f);
        transition.advance(1_000_000_000L + (MeshGradientTransition.DURATION_MS + 1L) * 1_000_000L);
        assertFalse(transition.active());
        assertEquals(to[0], transition.colors()[0], 0f);
        assertEquals(2f, transition.phase(), 0f);
        assertEquals(1.25f, transition.speed(), 0f);
    }

    @Test public void settlingWithoutFramesReachesTargetAndRejectsStalePalette() {
        MeshGradientTransition transition = new MeshGradientTransition(new float[40], 2, 0f, 1f, 1L);
        float[] target = MeshGradientShader.packColors(new int[]{0xffff0000, 0xff00ff00});
        transition.begin(2L, 100L, 2f, 1.2f, target, 2);
        assertTrue(transition.finish());
        assertArrayEquals(target, transition.colors(), 0f);
        assertEquals(2f, transition.phase(), 0f);
        assertEquals(1.2f, transition.speed(), 0f);
        assertFalse(transition.active());
        assertFalse(transition.finish());
        assertFalse(transition.begin(1L, 200L, 0f, 1f, new float[40], 2));
        assertArrayEquals(target, transition.colors(), 0f);
    }

    @Test public void staleGenerationCannotOverwriteNewTrack() {
        MeshGradientTransition transition = new MeshGradientTransition(new float[40], 2, 0f, 1f, 4L);
        assertTrue(transition.begin(5L, 0L, 1f, 1.1f, new float[40], 2));
        assertFalse(transition.begin(4L, 100_000_000L, 2f, 0.9f, new float[40], 2));
        assertTrue(transition.accepts(5L));
        assertFalse(transition.accepts(4L));
    }

    @Test public void transitionDoesNotAllocateDuringAdvance() {
        MeshGradientTransition transition = new MeshGradientTransition(new float[40], 2, 0f, 1f, 1L);
        transition.begin(2L, 0L, 1f, 1.1f, new float[40], 2);
        for (int i = 0; i < 100; i++) transition.advance(i * 10_000_000L);
        assertEquals(2, transition.count());
    }
}
