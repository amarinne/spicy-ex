package com.eza.spicyex.ui;

import org.junit.Test;
import java.util.Map;
import static org.junit.Assert.*;

public class FrameDeltaHistogramTest {
    private static long value(Map<String, Object> snapshot, String key) {
        return (long) snapshot.get(key);
    }

    @Test public void windowExcludesWarmupAndEndAndUsesNearestRankUpperBounds() {
        FrameDeltaHistogram histogram = new FrameDeltaHistogram(1_000_000, 10_000_000, 100_000_000);
        histogram.recordFrame(1);
        long time = 10_000_000;
        histogram.recordFrame(time);
        for (int i : new int[]{10, 1, 9, 2, 8, 3, 7, 4, 6, 5})
            histogram.recordFrame(time += i * 100_000L + 1);
        histogram.recordFrame(100_000_000);
        Map<String, Object> result = histogram.snapshot();
        assertEquals(11, value(result, "callbacks"));
        assertEquals(10, value(result, "count"));
        assertEquals(100_001, value(result, "min_ns"));
        assertEquals(600_000, value(result, "p50_ns"));
        assertEquals(1_000_000, value(result, "p90_ns"));
        assertEquals(1_000_001, value(result, "p99_ns"));
        assertEquals(1_000_001, value(result, "max_ns"));
    }

    @Test public void exactBoundaryAndOverflowStayBounded() {
        FrameDeltaHistogram histogram = new FrameDeltaHistogram(10_000_000, 1, 1_000_000_000);
        histogram.recordFrame(1);
        histogram.recordFrame(100_001);
        histogram.recordFrame(250_100_001);
        histogram.recordFrame(550_100_001);
        Map<String, Object> result = histogram.snapshot();
        long[] buckets = (long[]) result.get("buckets");
        assertEquals(2501, buckets.length);
        assertEquals(1, buckets[0]);
        assertEquals(1, buckets[2499]);
        assertEquals(1, value(result, "overflow_count"));
        assertEquals(300_000_000, value(result, "p90_ns"));
        buckets[0] = 99;
        assertEquals(1, ((long[]) histogram.snapshot().get("buckets"))[0]);
    }

    @Test public void countsMissingSlotsAndRejectsNonIncreasingTimestamps() {
        FrameDeltaHistogram histogram = new FrameDeltaHistogram(10_000_000, 1, 100_000_000);
        histogram.recordFrame(1);
        histogram.recordFrame(10_000_001);
        histogram.recordFrame(40_000_001);
        histogram.recordFrame(40_000_001);
        histogram.recordFrame(30_000_001);
        assertEquals(2, value(histogram.snapshot(), "dropped_callback_estimate"));
        assertEquals(2, value(histogram.snapshot(), "invalid_timestamps"));
        histogram.interrupt();
        histogram.recordFrame(80_000_001);
        assertEquals(2, value(histogram.snapshot(), "count"));
        assertEquals(1, value(histogram.snapshot(), "interruptions"));
    }

    @Test public void snapshotsStayConsistentDuringRecording() throws Exception {
        FrameDeltaHistogram histogram = new FrameDeltaHistogram(100_000, 1, 2_000_000_000);
        Thread writer = new Thread(() -> {
            for (int i = 0; i < 10_000; i++) histogram.recordFrame(1L + i * 100_000L);
        });
        writer.start();
        for (int i = 0; i < 100; i++) {
            Map<String, Object> snapshot = histogram.snapshot();
            long sum = 0;
            for (long bucket : (long[]) snapshot.get("buckets")) sum += bucket;
            assertEquals(value(snapshot, "count"), sum);
        }
        writer.join();
        assertEquals(9999, value(histogram.snapshot(), "count"));
    }

    @Test public void emptySnapshotHasNoInventedTiming() {
        FrameDeltaHistogram histogram = new FrameDeltaHistogram(1, 1, 2);
        assertEquals(0, value(histogram.snapshot(), "min_ns"));
        assertEquals(0, value(histogram.snapshot(), "p90_ns"));
        assertEquals(0, value(histogram.snapshot(), "max_ns"));
    }
}
