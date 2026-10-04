package com.eza.spicyex.lyrics;

import org.junit.Test;
import static org.junit.Assert.*;

public class SoftLyricSweepTest {
    @Test public void sweepStartsBeforeSentenceAndEndsAfterIt() {
        assertEquals(-80f, SoftLyricSweep.start(200f, 0f), .001f);
        assertEquals(0f, SoftLyricSweep.start(200f, 0f) + SoftLyricSweep.band(200f), .001f);
        assertEquals(200f, SoftLyricSweep.start(200f, 1f), .001f);
        assertEquals(60f, SoftLyricSweep.start(200f, .5f), .001f);
    }
    @Test public void bandScalesWithSentenceWidthAndClampsProgress() {
        assertEquals(40f, SoftLyricSweep.band(100f), .001f);
        assertEquals(160f, SoftLyricSweep.band(400f), .001f);
        assertEquals(-40f, SoftLyricSweep.start(100f, -2f), .001f);
        assertEquals(100f, SoftLyricSweep.start(100f, 2f), .001f);
        assertTrue(SoftLyricSweep.band(0f) > 0f);
        assertTrue(SoftLyricSweep.UNSUNG_ALPHA >= .55f);
        assertTrue(SoftLyricSweep.MIDDLE_ALPHA > SoftLyricSweep.UNSUNG_ALPHA);
    }
}
