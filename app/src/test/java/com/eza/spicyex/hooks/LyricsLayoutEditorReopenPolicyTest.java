package com.eza.spicyex.hooks;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class LyricsLayoutEditorReopenPolicyTest {

    @Before
    public void setUp() {
        LyricsLayoutEditorReopenPolicy.clear();
    }

    @Test
    public void freshRecordReopensOnce() {
        LyricsLayoutEditorReopenPolicy.record(false, "artwork", 1000L);
        assertTrue(LyricsLayoutEditorReopenPolicy.hasPending());

        LyricsLayoutEditorReopenPolicy.PendingReopen reopen =
                LyricsLayoutEditorReopenPolicy.consume(2000L);
        assertNotNull(reopen);
        assertFalse(reopen.cardMode);
        assertEquals("artwork", reopen.selectedName);
        assertEquals(1000L, reopen.elapsedRealtimeMs);

        // Record consumed once
        assertFalse(LyricsLayoutEditorReopenPolicy.hasPending());
    }

    @Test
    public void secondConsumeReturnsNothing() {
        LyricsLayoutEditorReopenPolicy.record(true, "card", 1000L);
        assertNotNull(LyricsLayoutEditorReopenPolicy.consume(2000L));
        assertNull(LyricsLayoutEditorReopenPolicy.consume(2000L));
        assertFalse(LyricsLayoutEditorReopenPolicy.hasPending());
    }

    @Test
    public void staleRecordDrops() {
        LyricsLayoutEditorReopenPolicy.record(false, "skip", 1000L);
        // 5001ms elapsed -> stale (> 5000ms)
        LyricsLayoutEditorReopenPolicy.PendingReopen reopen =
                LyricsLayoutEditorReopenPolicy.consume(6001L);
        assertNull(reopen);
        assertFalse(LyricsLayoutEditorReopenPolicy.hasPending());
    }

    @Test
    public void boundaryAgeChecks() {
        // Just under 5 seconds: fresh
        LyricsLayoutEditorReopenPolicy.record(false, "lyrics", 1000L);
        assertNotNull(LyricsLayoutEditorReopenPolicy.consume(5999L));

        // Exactly 5 seconds: dropped
        LyricsLayoutEditorReopenPolicy.record(false, "lyrics", 1000L);
        assertNull(LyricsLayoutEditorReopenPolicy.consume(6000L));

        // Negative elapsed time: dropped
        LyricsLayoutEditorReopenPolicy.record(false, "lyrics", 2000L);
        assertNull(LyricsLayoutEditorReopenPolicy.consume(1000L));
    }

    @Test
    public void normalCloseClearsPendingRecord() {
        LyricsLayoutEditorReopenPolicy.record(false, "focus", 1000L);
        assertTrue(LyricsLayoutEditorReopenPolicy.hasPending());

        LyricsLayoutEditorReopenPolicy.clear();
        assertFalse(LyricsLayoutEditorReopenPolicy.hasPending());
        assertNull(LyricsLayoutEditorReopenPolicy.consume(1500L));
    }

    @Test
    public void handlesNullSelectedNameSafely() {
        LyricsLayoutEditorReopenPolicy.record(false, null, 1000L);
        LyricsLayoutEditorReopenPolicy.PendingReopen reopen =
                LyricsLayoutEditorReopenPolicy.consume(2000L);
        assertNotNull(reopen);
        assertEquals("", reopen.selectedName);
    }
}
