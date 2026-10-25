package com.eza.spicyex.hooks;

import org.junit.Test;
import java.util.Map;
import static org.junit.Assert.*;

public class PrepareDocumentTimingTest {
    private static long calls(Map<String, Object> snapshot) {
        return (Long) snapshot.get("prepare_calls");
    }

    private static double ms(Map<String, Object> snapshot, String key) {
        return (Double) snapshot.get(key);
    }

    @Test public void reportsCountMedianP90MaxAndLastInMilliseconds() {
        PrepareDocumentTiming timing = PrepareDocumentTiming.get();
        timing.reset();
        for (long nanos : new long[]{5_000_000L, 1_000_000L, 3_000_000L, 2_000_000L, 4_000_000L}) {
            timing.record(nanos);
        }
        Map<String, Object> snapshot = timing.snapshot();
        assertEquals(5L, calls(snapshot));
        assertEquals(5L, (long) (Long) snapshot.get("prepare_sampled"));
        assertEquals(3.0d, ms(snapshot, "prepare_median_ms"), 0.0001d);
        assertEquals(5.0d, ms(snapshot, "prepare_p90_ms"), 0.0001d);
        assertEquals(5.0d, ms(snapshot, "prepare_max_ms"), 0.0001d);
        assertEquals(4.0d, ms(snapshot, "prepare_last_ms"), 0.0001d);
        timing.reset();
    }

    @Test public void evenCountTakesTheLowMiddleNearestRank() {
        PrepareDocumentTiming timing = PrepareDocumentTiming.get();
        timing.reset();
        for (long nanos : new long[]{4_000_000L, 1_000_000L, 3_000_000L, 2_000_000L}) {
            timing.record(nanos);
        }
        assertEquals(2.0d, ms(timing.snapshot(), "prepare_median_ms"), 0.0001d);
        timing.reset();
    }

    @Test public void percentilesUseTheMostRecentCallsWhenTheRingWraps() {
        PrepareDocumentTiming timing = PrepareDocumentTiming.get();
        timing.reset();
        timing.record(50_000_000L);
        for (int i = 1; i <= PrepareDocumentTiming.CAPACITY; i++) timing.record(i * 1_000L);
        Map<String, Object> snapshot = timing.snapshot();
        assertEquals(PrepareDocumentTiming.CAPACITY + 1L, calls(snapshot));
        assertEquals((long) PrepareDocumentTiming.CAPACITY, (long) (Long) snapshot.get("prepare_sampled"));
        assertEquals(50.0d, ms(snapshot, "prepare_max_ms"), 0.0001d);
        assertEquals(2.048d, ms(snapshot, "prepare_median_ms"), 0.0001d);
        assertEquals(3.687d, ms(snapshot, "prepare_p90_ms"), 0.0001d);
        assertEquals(4.096d, ms(snapshot, "prepare_last_ms"), 0.0001d);
        timing.reset();
    }

    @Test public void resetClearsTheWindow() {
        PrepareDocumentTiming timing = PrepareDocumentTiming.get();
        timing.record(9_000_000L);
        timing.reset();
        Map<String, Object> snapshot = timing.snapshot();
        assertEquals(0L, calls(snapshot));
        assertEquals(0.0d, ms(snapshot, "prepare_median_ms"), 0.0001d);
        assertEquals(0.0d, ms(snapshot, "prepare_max_ms"), 0.0001d);
    }

    @Test public void snapshotsStayConsistentDuringRecording() throws Exception {
        PrepareDocumentTiming timing = PrepareDocumentTiming.get();
        timing.reset();
        Thread writer = new Thread(() -> {
            for (int i = 0; i < 5_000; i++) timing.record(1_000_000L + i);
        });
        writer.start();
        for (int i = 0; i < 100; i++) {
            Map<String, Object> snapshot = timing.snapshot();
            long sampled = (Long) snapshot.get("prepare_sampled");
            assertTrue(sampled >= 0L && sampled <= PrepareDocumentTiming.CAPACITY);
            assertTrue(ms(snapshot, "prepare_p90_ms") <= ms(snapshot, "prepare_max_ms"));
        }
        writer.join();
        assertEquals(5_000L, calls(timing.snapshot()));
        timing.reset();
    }
}
