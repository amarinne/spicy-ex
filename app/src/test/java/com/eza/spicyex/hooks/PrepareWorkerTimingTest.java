package com.eza.spicyex.hooks;

import org.junit.Test;
import java.util.Map;
import static org.junit.Assert.assertEquals;

public class PrepareWorkerTimingTest {
    @Test public void workerCompletionDoesNotCountAsMainPostCompletion() {
        PrepareWorkerTiming timing = new PrepareWorkerTiming();
        long window = timing.window();
        for (long ms : new long[]{5, 1, 3, 2, 4}) timing.recordWorker(window, ms * 1_000_000L);
        assertEquals(0L, timing.completedPosts());
        timing.recordHop(window, 12_000_000L);

        Map<String, Object> snapshot = timing.snapshot();
        assertEquals(5L, snapshot.get("prepare_worker_count"));
        assertEquals(5L, snapshot.get("prepare_worker_sampled"));
        assertEquals(3.0d, (Double) snapshot.get("prepare_worker_median_ms"), 0.0001d);
        assertEquals(5.0d, (Double) snapshot.get("prepare_worker_p90_ms"), 0.0001d);
        assertEquals(5.0d, (Double) snapshot.get("prepare_worker_max_ms"), 0.0001d);
        assertEquals(1L, timing.completedPosts());
        assertEquals(12.0d, (Double) snapshot.get("prepare_hop_median_ms"), 0.0001d);
    }

    @Test public void oldPhaseCompletionsCannotPolluteNewWindow() {
        PrepareWorkerTiming timing = new PrepareWorkerTiming();
        long oldWindow = timing.window();
        timing.recordWorker(oldWindow, 9_000_000L);
        timing.reset();
        timing.recordWorker(oldWindow, 20_000_000L);
        timing.recordHop(oldWindow, 30_000_000L);
        assertEquals(0L, timing.completedPosts());
        assertEquals(0L, timing.snapshot().get("prepare_worker_count"));
        assertEquals(0.0d, (Double) timing.snapshot().get("prepare_worker_max_ms"), 0.0001d);
        timing.recordWorker(timing.window(), 2_000_000L);
        timing.recordHop(timing.window(), 3_000_000L);
        assertEquals(1L, timing.completedPosts());
    }

    @Test public void ringKeepsRecentPercentilesAndWholeWindowMaximum() {
        PrepareWorkerTiming timing = new PrepareWorkerTiming();
        long window = timing.window();
        timing.recordWorker(window, 50_000_000L);
        for (int i = 1; i <= PrepareDocumentTiming.CAPACITY; i++) timing.recordWorker(window, i * 1_000L);
        Map<String, Object> snapshot = timing.snapshot();
        assertEquals(4097L, snapshot.get("prepare_worker_count"));
        assertEquals(4096L, snapshot.get("prepare_worker_sampled"));
        assertEquals(2.048d, (Double) snapshot.get("prepare_worker_median_ms"), 0.0001d);
        assertEquals(3.687d, (Double) snapshot.get("prepare_worker_p90_ms"), 0.0001d);
        assertEquals(50.0d, (Double) snapshot.get("prepare_worker_max_ms"), 0.0001d);
    }
}
