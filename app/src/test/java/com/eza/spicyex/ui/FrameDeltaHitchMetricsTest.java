package com.eza.spicyex.ui;

import org.junit.Test;
import java.util.Map;
import static org.junit.Assert.*;

public class FrameDeltaHitchMetricsTest {
    private static long value(Map<String, Object> snapshot, String key) {
        return (long) snapshot.get(key);
    }

    @Test public void maxIsTheLongestFrameDelta() {
        FrameDeltaHistogram histogram = new FrameDeltaHistogram(10_000_000L, 1L, 1_000_000_000L);
        long time = 1L;
        histogram.recordFrame(time);
        for (long delta : new long[]{5_000_000L, 12_000_000L, 40_000_000L, 8_000_000L}) {
            time += delta;
            histogram.recordFrame(time);
        }
        assertEquals(40_000_000L, value(histogram.snapshot(), "max_ns"));
    }

    @Test public void overOneAndAHalfVsyncIsStrictlyAboveTheThreshold() {
        long period = 10_000_000L;
        FrameDeltaHistogram histogram = new FrameDeltaHistogram(period, 1L, 1_000_000_000L);
        long time = 1L;
        histogram.recordFrame(time);
        time += 5_000_000L;      // 0.5 x
        histogram.recordFrame(time);
        time += 15_000_000L;     // exactly 1.5 x: not counted
        histogram.recordFrame(time);
        time += 15_000_001L;     // one nanosecond over
        histogram.recordFrame(time);
        time += 25_000_000L;     // clearly over
        histogram.recordFrame(time);
        Map<String, Object> snapshot = histogram.snapshot();
        assertEquals(4L, value(snapshot, "count"));
        assertEquals(2L, value(snapshot, "over_1_5x_vsync"));
        assertEquals(25_000_000L, value(snapshot, "max_ns"));
    }

    @Test public void emptyWindowCountsNothingOver() {
        FrameDeltaHistogram histogram = new FrameDeltaHistogram(16_666_667L, 1L, 2L);
        Map<String, Object> snapshot = histogram.snapshot();
        assertEquals(0L, value(snapshot, "over_1_5x_vsync"));
        assertEquals(0L, value(snapshot, "max_ns"));
    }

    @Test public void aRefreshChangeMovesTheThresholdForLaterFrames() {
        FrameDeltaHistogram histogram = new FrameDeltaHistogram(8_333_333L, 1L, 1_000_000_000L);
        histogram.recordFrame(1L);
        histogram.recordFrame(1L + 14_000_000L);
        assertEquals(1L, value(histogram.snapshot(), "over_1_5x_vsync"));
        histogram.setPeriodNanos(16_666_667L);
        histogram.recordFrame(1L + 28_000_000L);
        Map<String, Object> snapshot = histogram.snapshot();
        assertEquals(2L, value(snapshot, "count"));
        assertEquals(1L, value(snapshot, "over_1_5x_vsync"));
        assertEquals(16_666_667L, value(snapshot, "period_ns"));
        assertThrows(IllegalArgumentException.class, () -> histogram.setPeriodNanos(0L));
    }
}
