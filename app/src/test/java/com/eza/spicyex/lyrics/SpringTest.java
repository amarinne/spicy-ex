package com.eza.spicyex.lyrics;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SpringTest {
    @Test
    public void springReportsRestAndLeavesRestAfterGoalChange() {
        Spring spring = new Spring(1f, 1f, 0.7f);
        assertTrue(spring.isAtRest(0.001f, 0.001f));

        spring.setGoal(2f);
        assertFalse(spring.isAtRest(0.001f, 0.001f));
        for (int i = 0; i < 600; i++) spring.step(1f / 120f);

        assertTrue(spring.isAtRest(0.0025f, 0.0025f));
    }

    @Test
    public void snapResetsPositionAndVelocity() {
        Spring spring = new Spring(0f, 1f, 0.7f);
        spring.setGoal(1f);
        spring.step(1f / 60f);
        spring.snap(3f);

        assertTrue(spring.isAtRest(0f, 0f));
    }
}
