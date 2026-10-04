package com.eza.spicyex.hooks;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Fallback latency compensation scales with speed only for live PlayerState positions. */
public class PlaybackLatencyCompensationTest {
    @Test
    public void doubleSpeedScalesLatency() {
        assertEquals(9600L,
                PlaybackBridge.compensateForLatency(10000L, 200L, 2d, true, false));
    }

    @Test
    public void halfSpeedScalesLatency() {
        assertEquals(9900L,
                PlaybackBridge.compensateForLatency(10000L, 200L, 0.5d, true, false));
    }

    @Test
    public void bufferingKeepsUnscaledSubtraction() {
        assertEquals(9800L,
                PlaybackBridge.compensateForLatency(10000L, 200L, 2d, true, true));
    }

    @Test
    public void otherSourceKeepsUnscaledSubtraction() {
        assertEquals(9800L,
                PlaybackBridge.compensateForLatency(10000L, 200L, 2d, false, false));
    }

    @Test
    public void clampsToNonnegative() {
        assertEquals(0L,
                PlaybackBridge.compensateForLatency(100L, 200L, 2d, true, false));
        assertEquals(0L,
                PlaybackBridge.compensateForLatency(50L, 200L, 1d, false, false));
    }
}
