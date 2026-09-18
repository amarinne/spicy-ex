package com.eza.spicyex.lyrics.session;

import android.content.ComponentCallbacks2;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;

public class LyricsMemoryPressureTest {
    @Test
    public void dispatchRunsReclaimersAtRunningLowOrStronger() {
        AtomicInteger calls = new AtomicInteger();
        LyricsMemoryPressure.Reclaimer reclaimer = level -> calls.incrementAndGet();
        LyricsMemoryPressure.addReclaimer(reclaimer);
        try {
            LyricsMemoryPressure.dispatch(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW);
            assertEquals(1, calls.get());

            LyricsMemoryPressure.dispatch(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN);
            assertEquals(2, calls.get());

            LyricsMemoryPressure.dispatch(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND);
            assertEquals(3, calls.get());

            LyricsMemoryPressure.dispatch(5);
            assertEquals(3, calls.get());
        } finally {
            LyricsMemoryPressure.removeReclaimer(reclaimer);
        }
    }
}
