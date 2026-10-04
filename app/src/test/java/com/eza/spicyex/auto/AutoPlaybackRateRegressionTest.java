package com.eza.spicyex.auto;

import android.os.Bundle;
import org.junit.Test;
import static org.junit.Assert.*;

public class AutoPlaybackRateRegressionTest {
    @Test public void bufferingFreezesPositionWithoutStartingPauseRelease() {
        AutoPrototypePolicy.PlaybackHold hold = new AutoPrototypePolicy.PlaybackHold();
        assertTrue(hold.update(true, true, 1000));
        assertTrue(hold.update(true, true, 6000));
        assertEquals(2500, AutoPrototypePolicy.position(2500, 1000, true, 0d, 6000));
    }
    @Test public void speedAndPauseUseElapsedSamplesConsistently() {
        assertEquals(3500, AutoPrototypePolicy.position(2500, 1000, true, 2d, 1500));
        assertEquals(2750, AutoPrototypePolicy.position(2500, 1000, true, .5d, 1500));
        assertEquals(2500, AutoPrototypePolicy.position(2500, 1000, false, 2d, 1500));
        assertEquals(2500, AutoPrototypePolicy.position(2500, 1000, true, 2d, 900));
    }
    @Test public void untrustedRatesUseTheSharedClockBounds() {
        for (double rate : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1, 9})
            assertEquals(3000, AutoPrototypePolicy.position(2500, 1000, true, rate, 1500));
        assertEquals(6500, AutoPrototypePolicy.position(2500, 1000, true, 8d, 1500));
    }
    @Test public void providerSanitizesRateAndKeepsElapsedSample() throws Exception {
        java.lang.reflect.Method sanitize = AutoPrototypeProvider.class.getDeclaredMethod("sanitize", Bundle.class);
        sanitize.setAccessible(true);
        Bundle packet = new Bundle(); packet.putDouble("playbackRate", Double.POSITIVE_INFINITY);
        packet.putLong("sampledAt", 1234); packet.putBoolean("playing", true);
        Bundle clean = (Bundle) sanitize.invoke(null, packet);
        assertEquals(1d, clean.getDouble("playbackRate", -1d), 0d);
        assertEquals(1234, clean.getLong("sampledAt"));
        packet.putDouble("playbackRate", 0d);
        clean = (Bundle) sanitize.invoke(null, packet);
        assertEquals(0d, clean.getDouble("playbackRate", -1d), 0d);
        assertTrue(clean.getBoolean("playing"));
    }
    @Test public void prefetchedRowCannotOverwriteTheSessionRate() {
        Bundle packet = new Bundle(); packet.putDouble("playbackRate", 0d);
        Bundle row = new Bundle(); row.putString("line", "next"); row.putDouble("playbackRate", 1d);
        Bundle selected = AutoLyricsView.selectNext(packet, AutoPrototypeProvider.sanitizeRow(row));
        assertEquals(0d, selected.getDouble("playbackRate", 1d), 0d);
        assertEquals("next", selected.getString("line"));
    }
}
