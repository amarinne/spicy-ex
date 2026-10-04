package com.eza.spicyex.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.eza.spicyex.References;

import org.junit.After;
import org.junit.Test;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;

/** Failed PlayerState parses must not pin identity or half-update the cached snapshot. */
public class PlaybackBridgeStateCacheTest {

    private WeakReference<Object> savedState;

    @After
    public void restorePlayerState() {
        References.playerState = savedState == null ? new WeakReference<>(null) : savedState;
    }

    @Test
    public void failingAccessorDoesNotPublishIdentityAndRetries() throws Exception {
        savedState = References.playerState;
        PlaybackBridge bridge = new PlaybackBridge();
        FlakyState state = new FlakyState(5000L, System.currentTimeMillis(), 1d, false);
        state.failPosition = true;
        References.playerState = new WeakReference<>(state);

        assertEquals(-1L, readProgress(bridge, false));
        assertNotSame(state, parsedStateOf(bridge));
        assertEquals(-1L, parsedBasePosOf(bridge));

        state.failPosition = false;
        assertEquals(5000L, readProgress(bridge, false));
        assertSame(state, parsedStateOf(bridge));
    }

    @Test
    public void failingTimestampKeepsOldSnapshotInsteadOfMixing() throws Exception {
        savedState = References.playerState;
        PlaybackBridge bridge = new PlaybackBridge();
        FlakyState good = new FlakyState(1000L, System.currentTimeMillis(), 1d, false);
        References.playerState = new WeakReference<>(good);
        assertEquals(1000L, readProgress(bridge, false));
        assertSame(good, parsedStateOf(bridge));

        FlakyState bad = new FlakyState(2000L, System.currentTimeMillis(), 1d, false);
        bad.failTimestamp = true;
        References.playerState = new WeakReference<>(bad);
        assertEquals(-1L, readProgress(bridge, false));
        assertSame(good, parsedStateOf(bridge));
        assertEquals(1000L, parsedBasePosOf(bridge));

        bad.failTimestamp = false;
        assertEquals(2000L, readProgress(bridge, false));
        assertSame(bad, parsedStateOf(bridge));
    }

    @Test
    public void absentSpeedAndBufferingFallBack() throws Exception {
        savedState = References.playerState;
        PlaybackBridge bridge = new PlaybackBridge();
        NoSpeedState state = new NoSpeedState(3000L, System.currentTimeMillis());
        References.playerState = new WeakReference<>(state);

        assertEquals(3000L, readProgress(bridge, false));
        assertEquals(1d, parsedSpeedOf(bridge), 0.0d);
        assertEquals(false, parsedBufferingOf(bridge));
        assertTrue(readProgress(bridge, true) >= 3000L);
    }

    private static long readProgress(PlaybackBridge bridge, boolean playing) throws Exception {
        Method m = PlaybackBridge.class.getDeclaredMethod("readPlayerStateProgressMs", boolean.class);
        m.setAccessible(true);
        return (Long) m.invoke(bridge, playing);
    }

    private static Object parsedStateOf(PlaybackBridge bridge) throws Exception {
        return field(bridge, "parsedState");
    }

    private static long parsedBasePosOf(PlaybackBridge bridge) throws Exception {
        return (Long) field(bridge, "parsedBasePos");
    }

    private static double parsedSpeedOf(PlaybackBridge bridge) throws Exception {
        return (Double) field(bridge, "parsedSpeed");
    }

    private static boolean parsedBufferingOf(PlaybackBridge bridge) throws Exception {
        return (Boolean) field(bridge, "parsedBuffering");
    }

    private static Object field(PlaybackBridge bridge, String name) throws Exception {
        java.lang.reflect.Field f = PlaybackBridge.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(bridge);
    }

    public static final class FlakyState {
        volatile boolean failPosition;
        volatile boolean failTimestamp;
        final long position;
        final long timestamp;
        final double speed;
        final boolean buffering;

        FlakyState(long position, long timestamp, double speed, boolean buffering) {
            this.position = position;
            this.timestamp = timestamp;
            this.speed = speed;
            this.buffering = buffering;
        }

        public Long positionAsOfTimestamp() {
            if (failPosition) throw new IllegalStateException("pos boom");
            return position;
        }

        public Long timestamp() {
            if (failTimestamp) throw new IllegalStateException("ts boom");
            return timestamp;
        }

        public Double playbackSpeed() {
            return speed;
        }

        public Boolean isBuffering() {
            return buffering;
        }
    }

    public static final class NoSpeedState {
        final long position;
        final long timestamp;

        NoSpeedState(long position, long timestamp) {
            this.position = position;
            this.timestamp = timestamp;
        }

        public Long positionAsOfTimestamp() {
            return position;
        }

        public Long timestamp() {
            return timestamp;
        }
    }
}
