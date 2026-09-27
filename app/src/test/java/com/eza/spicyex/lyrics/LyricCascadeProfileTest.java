package com.eza.spicyex.lyrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Locks the shape of the Apple-style row cascade: one shared spring, the focus and every row ahead
 * of it leave together, and rows behind it are released in order with a shrinking, bounded gap.
 * The exact constants are free to move; these invariants are not.
 */
public class LyricCascadeProfileTest {

    private static final float HZ = 1.6f;
    private static final float DAMPING = 0.84f;
    private static final float STAGGER = 0.045f;
    private static final float MAX_DELAY = 0.32f;

    private static LyricCascadeProfile at(float trailingRows) {
        return LyricCascadeProfile.forRow(trailingRows, HZ, DAMPING, STAGGER, MAX_DELAY);
    }

    @Test
    public void focusAndLeadingRowsLeaveTogether() {
        assertEquals(0f, at(0f).delaySeconds, 1e-6f);
        assertEquals(0f, at(-3f).delaySeconds, 1e-6f);
    }

    @Test
    public void trailingRowsAreReleasedInOrderWithAShrinkingGap() {
        assertEquals(STAGGER, at(1f).delaySeconds, 1e-6f);
        float previousGap = Float.MAX_VALUE;
        for (int n = 1; n < 6; n++) {
            float gap = at(n).delaySeconds - at(n - 1).delaySeconds;
            assertTrue("row " + n + " must leave after the one before it", gap > 0f);
            assertTrue("the stagger must not grow with distance", gap <= previousGap + 1e-6f);
            previousGap = gap;
        }
    }

    @Test
    public void staggerIsBounded() {
        assertEquals(MAX_DELAY, at(60f).delaySeconds, 1e-6f);
    }

    @Test
    public void everyRowSharesTheSameSpring() {
        for (float n = -4f; n <= 10f; n += 1f) {
            assertEquals(HZ, at(n).frequencyHz, 1e-6f);
            assertEquals(DAMPING, at(n).damping, 1e-6f);
        }
    }

    @Test
    public void degenerateInputsStayInRange() {
        LyricCascadeProfile weird = LyricCascadeProfile.forRow(3f, 0f, 5f, -1f, -1f);
        assertEquals(0f, weird.delaySeconds, 1e-6f);
        assertTrue(weird.frequencyHz > 0f);
        assertTrue(weird.damping <= 1f);
    }
}
