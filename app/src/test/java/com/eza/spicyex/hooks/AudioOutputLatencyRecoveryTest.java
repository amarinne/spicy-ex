package com.eza.spicyex.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotNull;

import android.util.SparseArray;

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * A transient {@code currentTrack} sampling failure must not clear the resolved driver
 * symbols, so the next sample retries; only structural resolution failures disable them.
 */
public class AudioOutputLatencyRecoveryTest {
    private static final String DRIVER_CLASS = "com.spotify.playbacknative.AudioDriver";

    @Test
    public void transientSamplingFailureRetriesOnNextSample() throws Exception {
        FakeMap map = new FakeMap();
        map.add(new FakeDriver());
        FakeDriver.sSessionToAudioDriverMap = map;
        FakeDriver.sCurrentAudioSession = null;
        FakeDriver.failNextSampling = true;
        FakeDriver.samplingCalls = 0;
        try {
            AudioOutputLatency latency = resolvedLatency();
            assertNull(currentTrack(latency));
            assertEquals(1, FakeDriver.samplingCalls);
            assertNotNull(sessionMapField(latency));

            FakeDriver.failNextSampling = false;
            assertNull(currentTrack(latency));
            assertEquals(2, FakeDriver.samplingCalls);
            assertNotNull(sessionMapField(latency));
        } finally {
            FakeDriver.sSessionToAudioDriverMap = null;
            FakeDriver.failNextSampling = false;
        }
    }

    @Test
    public void emptyMapStaysSafeAndKeepsRetrying() throws Exception {
        FakeMap map = new FakeMap();
        FakeDriver.sSessionToAudioDriverMap = map;
        FakeDriver.sCurrentAudioSession = null;
        FakeDriver.failNextSampling = false;
        FakeDriver.samplingCalls = 0;
        try {
            AudioOutputLatency latency = resolvedLatency();
            assertNull(currentTrack(latency));
            assertNotNull(sessionMapField(latency));

            map.add(new FakeDriver());
            assertNull(currentTrack(latency));
            assertEquals(1, FakeDriver.samplingCalls);
            assertNotNull(sessionMapField(latency));
        } finally {
            FakeDriver.sSessionToAudioDriverMap = null;
        }
    }

    @Test
    public void structuralResolutionFailureDisablesPermanently() throws Exception {
        int[] loads = new int[1];
        ClassLoader missing = new ClassLoader(getClass().getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve)
                    throws ClassNotFoundException {
                if (DRIVER_CLASS.equals(name)) {
                    loads[0]++;
                    throw new ClassNotFoundException(name);
                }
                return super.loadClass(name, resolve);
            }
        };
        AudioOutputLatency latency = new AudioOutputLatency(missing);
        assertNull(currentTrack(latency));
        assertNull(currentTrack(latency));
        assertEquals(1, loads[0]);
        assertNull(sessionMapField(latency));
    }

    /**
     * Simulates a successful reflection resolution by injecting the driver symbols,
     * so the tests below exercise only the sampling phase.
     */
    private static AudioOutputLatency resolvedLatency() throws Exception {
        AudioOutputLatency latency =
                new AudioOutputLatency(AudioOutputLatencyRecoveryTest.class.getClassLoader());
        set(latency, "resolved", true);
        set(latency, "sessionMapField",
                FakeDriver.class.getDeclaredField("sSessionToAudioDriverMap"));
        set(latency, "currentSessionField",
                FakeDriver.class.getDeclaredField("sCurrentAudioSession"));
        set(latency, "getAudioTrack", FakeDriver.class.getMethod("getAudioTrack"));
        return latency;
    }

    private static void set(AudioOutputLatency latency, String field, Object value)
            throws Exception {
        Field f = AudioOutputLatency.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(latency, value);
    }

    private static Object currentTrack(AudioOutputLatency latency) throws Exception {
        Method currentTrack = AudioOutputLatency.class.getDeclaredMethod("currentTrack");
        currentTrack.setAccessible(true);
        return currentTrack.invoke(latency);
    }

    private static Object sessionMapField(AudioOutputLatency latency) throws Exception {
        Field field = AudioOutputLatency.class.getDeclaredField("sessionMapField");
        field.setAccessible(true);
        return field.get(latency);
    }

    public static final class FakeDriver {
        public static Object sSessionToAudioDriverMap;
        public static Object sCurrentAudioSession;
        static volatile boolean failNextSampling;
        static volatile int samplingCalls;

        public Object getAudioTrack() {
            samplingCalls++;
            if (failNextSampling) throw new IllegalStateException("transient sample");
            return null;
        }
    }

    static final class FakeMap extends SparseArray<Object> {
        private final List<Object> values = new ArrayList<>();

        void add(Object driver) {
            values.add(driver);
        }

        @Override
        public int size() {
            return values.size();
        }

        @Override
        public Object valueAt(int index) {
            return values.get(index);
        }
    }
}
