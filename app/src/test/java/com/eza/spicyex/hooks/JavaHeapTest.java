package com.eza.spicyex.hooks;

import org.junit.Test;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import static org.junit.Assert.*;

public class JavaHeapTest {
    /** The report copies the snapshot in as it is, so the snapshot names are the JSON names. */
    @Test public void snapshotUsesTheDocumentedJsonFieldNames() {
        Map<String, Object> snapshot = JavaHeap.snapshot();
        assertEquals(new HashSet<>(Arrays.asList("heap_used_kb", "heap_max_kb")),
                snapshot.keySet());
        assertTrue(snapshot.get("heap_used_kb") instanceof Long);
        assertTrue(snapshot.get("heap_max_kb") instanceof Long);
    }

    @Test public void theUsedHeapStaysUnderTheProcessCeiling() {
        long used = JavaHeap.usedKb();
        long max = JavaHeap.maxKb();
        assertTrue(used >= 0L);
        assertTrue(max > 0L);
        assertTrue(used <= max);
    }
}
