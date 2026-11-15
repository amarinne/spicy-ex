package com.eza.spicyex.auto;

import org.junit.Test;
import static org.junit.Assert.*;

public class AutoFrameCadenceTest {
    @Test public void followsThirtyHzOnCommonDisplayClocks() {
        for (int refresh : new int[]{30, 60, 90, 120}) {
            long previous = 0;
            int rendered = 0;
            for (int tick = 0; tick < refresh * 10; tick++) {
                long time = 1_000_000_000L + Math.round(tick * 1e9 / refresh);
                if (AutoLyricsView.frameDue(previous, time)) { previous = time; rendered++; }
            }
            assertEquals("display refresh " + refresh, 300, rendered);
        }
    }

    @Test public void skipsEarlyVsyncButRendersImmediatelyAfterStallOrRestart() {
        assertFalse(AutoLyricsView.frameDue(1_000_000_000L, 1_016_666_667L));
        assertTrue(AutoLyricsView.frameDue(1_000_000_000L, 1_033_000_000L));
        assertTrue(AutoLyricsView.frameDue(1_000_000_000L, 1_100_000_000L));
        assertFalse(AutoLyricsView.frameDue(1_100_000_000L, 1_108_333_333L));
        assertTrue(AutoLyricsView.frameDue(0, 1_108_333_333L));
    }
}
