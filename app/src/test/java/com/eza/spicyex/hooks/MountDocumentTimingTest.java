package com.eza.spicyex.hooks;

import org.junit.Test;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import static org.junit.Assert.*;

public class MountDocumentTimingTest {
    private static Set<String> expectedFields() {
        return new HashSet<>(Arrays.asList(
                "mount_calls", "mount_sampled", "mount_median_ms", "mount_p90_ms",
                "mount_max_ms", "mount_last_ms", "mount_heap_used_kb", "mount_heap_growth_kb"));
    }

    /** The report copies the snapshot in as it is, so the snapshot names are the JSON names. */
    @Test public void snapshotUsesTheDocumentedJsonFieldNames() {
        MountDocumentTiming timing = new MountDocumentTiming();
        Map<String, Object> snapshot = timing.snapshot();
        assertEquals(expectedFields(), snapshot.keySet());
        assertTrue(snapshot.get("mount_calls") instanceof Long);
        assertTrue(snapshot.get("mount_sampled") instanceof Long);
        assertTrue(snapshot.get("mount_median_ms") instanceof Double);
        assertTrue(snapshot.get("mount_p90_ms") instanceof Double);
        assertTrue(snapshot.get("mount_max_ms") instanceof Double);
        assertTrue(snapshot.get("mount_last_ms") instanceof Double);
        assertTrue(snapshot.get("mount_heap_used_kb") instanceof Long);
        assertTrue(snapshot.get("mount_heap_growth_kb") instanceof Long);
    }

    @Test public void mountDurationsUseNearestRankPercentilesAndWholeWindowMaximum() {
        MountDocumentTiming timing = new MountDocumentTiming();
        for (long nanos : new long[]{5_000_000L, 1_000_000L, 3_000_000L, 2_000_000L, 4_000_000L}) {
            timing.record(nanos, 4_096L);
        }
        Map<String, Object> snapshot = timing.snapshot();
        assertEquals(5L, snapshot.get("mount_calls"));
        assertEquals(5L, snapshot.get("mount_sampled"));
        assertEquals(3.0d, (Double) snapshot.get("mount_median_ms"), 0.0001d);
        assertEquals(5.0d, (Double) snapshot.get("mount_p90_ms"), 0.0001d);
        assertEquals(5.0d, (Double) snapshot.get("mount_max_ms"), 0.0001d);
        assertEquals(4.0d, (Double) snapshot.get("mount_last_ms"), 0.0001d);
    }

    @Test public void evenCountTakesTheLowMiddleNearestRank() {
        MountDocumentTiming timing = new MountDocumentTiming();
        for (long nanos : new long[]{4_000_000L, 1_000_000L, 3_000_000L, 2_000_000L}) {
            timing.record(nanos, 4_096L);
        }
        assertEquals(2.0d, (Double) timing.snapshot().get("mount_median_ms"), 0.0001d);
    }

    @Test public void percentilesUseTheMostRecentMountsWhenTheRingWraps() {
        MountDocumentTiming timing = new MountDocumentTiming();
        timing.record(50_000_000L, 4_096L);
        for (int i = 1; i <= MountDocumentTiming.CAPACITY; i++) timing.record(i * 1_000L, 4_096L);
        Map<String, Object> snapshot = timing.snapshot();
        assertEquals(MountDocumentTiming.CAPACITY + 1L, snapshot.get("mount_calls"));
        assertEquals((long) MountDocumentTiming.CAPACITY, snapshot.get("mount_sampled"));
        assertEquals(50.0d, (Double) snapshot.get("mount_max_ms"), 0.0001d);
        assertEquals(2.048d, (Double) snapshot.get("mount_median_ms"), 0.0001d);
        assertEquals(3.687d, (Double) snapshot.get("mount_p90_ms"), 0.0001d);
        assertEquals(4.096d, (Double) snapshot.get("mount_last_ms"), 0.0001d);
    }

    /** The leak signal: the heap after the last mount minus the heap after the first. */
    @Test public void heapGrowthComparesTheFirstAndTheLastMount() {
        MountDocumentTiming timing = new MountDocumentTiming();
        timing.record(1_000_000L, 10_000L);
        timing.record(2_000_000L, 12_500L);
        Map<String, Object> snapshot = timing.snapshot();
        assertEquals(12_500L, snapshot.get("mount_heap_used_kb"));
        assertEquals(2_500L, snapshot.get("mount_heap_growth_kb"));
    }

    @Test public void anEmptyWindowReportsNoMountAndNoGrowth() {
        MountDocumentTiming timing = new MountDocumentTiming();
        Map<String, Object> snapshot = timing.snapshot();
        assertEquals(0L, snapshot.get("mount_calls"));
        assertEquals(0L, snapshot.get("mount_sampled"));
        assertEquals(0.0d, (Double) snapshot.get("mount_median_ms"), 0.0001d);
        assertEquals(0.0d, (Double) snapshot.get("mount_max_ms"), 0.0001d);
        assertEquals(0L, snapshot.get("mount_heap_used_kb"));
        assertEquals(0L, snapshot.get("mount_heap_growth_kb"));
    }

    @Test public void resetClearsTheWindow() {
        MountDocumentTiming timing = new MountDocumentTiming();
        timing.record(9_000_000L, 8_192L);
        timing.reset();
        Map<String, Object> snapshot = timing.snapshot();
        assertEquals(0L, snapshot.get("mount_calls"));
        assertEquals(0.0d, (Double) snapshot.get("mount_max_ms"), 0.0001d);
        assertEquals(0L, snapshot.get("mount_heap_used_kb"));
        assertEquals(0L, snapshot.get("mount_heap_growth_kb"));
    }
}
