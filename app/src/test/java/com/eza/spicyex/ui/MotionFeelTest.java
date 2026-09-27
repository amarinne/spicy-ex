package com.eza.spicyex.ui;

import static org.junit.Assert.assertEquals;

import org.junit.After;
import org.junit.Test;

/** Transition feel contract: Instant collapses, Fast halves, Relaxed keeps durations. */
public class MotionFeelTest {
    @After
    public void resetFeel() {
        Motion.setTransitionFeel(Motion.FEEL_FAST);
    }

    @Test
    public void relaxedKeepsTokenDurations() {
        Motion.setTransitionFeel(Motion.FEEL_RELAXED);
        assertEquals(Motion.BASE, Motion.dur(Motion.BASE));
        assertEquals(Motion.SWAP, Motion.dur(Motion.SWAP));
    }

    @Test
    public void fastHalvesTokenDurations() {
        Motion.setTransitionFeel(Motion.FEEL_FAST);
        assertEquals(Motion.BASE / 2, Motion.dur(Motion.BASE));
        assertEquals(Motion.SWAP / 2, Motion.dur(Motion.SWAP));
    }

    @Test
    public void instantCollapsesDurations() {
        Motion.setTransitionFeel(Motion.FEEL_INSTANT);
        assertEquals(0, Motion.dur(Motion.BASE));
        assertEquals(0, Motion.dur(Motion.SWAP));
    }

    @Test
    public void unknownFeelFallsBackToFast() {
        Motion.setTransitionFeel("Ludicrous");
        assertEquals(Motion.BASE / 2, Motion.dur(Motion.BASE));
        Motion.setTransitionFeel(null);
        assertEquals(Motion.BASE / 2, Motion.dur(Motion.BASE));
    }
}
