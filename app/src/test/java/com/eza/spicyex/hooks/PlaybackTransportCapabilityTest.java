package com.eza.spicyex.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

/**
 * Proves the playback transport capability boundary: a legacy {@link LyricsHost} still satisfies
 * the inherited commands, and the track-info readout accepts the narrower capability instead.
 */
public class PlaybackTransportCapabilityTest {
    private static final String[] COMMANDS = {
            "togglePlayPause", "skipToNextTrack", "skipToPreviousTrack"
    };

    @Test
    public void transportIsAPublicStandaloneCapability() {
        assertTrue(PlaybackTransportHost.class.isInterface());
        assertTrue(Modifier.isPublic(PlaybackTransportHost.class.getModifiers()));
        for (String name : COMMANDS) {
            Method command;
            try {
                command = PlaybackTransportHost.class.getMethod(name);
            } catch (NoSuchMethodException missing) {
                throw new AssertionError("missing transport command " + name, missing);
            }
            assertTrue(Modifier.isAbstract(command.getModifiers()));
            assertEquals(boolean.class, command.getReturnType());
            assertEquals(0, command.getParameterCount());
        }
    }

    @Test
    public void legacyLyricsHostInheritsTransportCommandsWithoutRedeclaration() throws Exception {
        assertTrue(PlaybackTransportHost.class.isAssignableFrom(LyricsHost.class));
        for (String name : COMMANDS) {
            Method inherited = LyricsHost.class.getMethod(name);
            assertEquals(PlaybackTransportHost.class, inherited.getDeclaringClass());
            assertTrue(Modifier.isPublic(inherited.getModifiers()));
            assertEquals(boolean.class, inherited.getReturnType());
            try {
                LyricsHost.class.getDeclaredMethod(name);
                fail(name + " should not be redeclared on LyricsHost");
            } catch (NoSuchMethodException expected) {
                // Inherited from PlaybackTransportHost.
            }
        }
    }

    @Test
    public void legacyLyricsHostProxyStillServesTransportCommands() {
        List<String> calls = new ArrayList<>();
        LyricsHost legacy = (LyricsHost) Proxy.newProxyInstance(
                LyricsHost.class.getClassLoader(),
                new Class<?>[]{LyricsHost.class},
                (proxy, method, args) -> {
                    calls.add(method.getName());
                    if ("togglePlayPause".equals(method.getName())) return true;
                    if ("skipToNextTrack".equals(method.getName())) return false;
                    if ("skipToPreviousTrack".equals(method.getName())) return true;
                    return null;
                });

        PlaybackTransportHost transport = legacy;
        assertTrue(transport.togglePlayPause());
        assertFalse(transport.skipToNextTrack());
        assertTrue(transport.skipToPreviousTrack());
        assertEquals(Arrays.asList(COMMANDS), calls);
    }

    @Test
    public void standaloneCapabilityFakePreservesCommandResults() {
        RecordingTransport fake = new RecordingTransport();
        PlaybackTransportHost transport = fake;

        assertTrue(transport.togglePlayPause());
        assertTrue(transport.skipToNextTrack());
        assertTrue(transport.skipToPreviousTrack());
        fake.result = false;
        assertFalse(transport.togglePlayPause());
        assertEquals(Arrays.asList("togglePlayPause", "skipToNextTrack",
                "skipToPreviousTrack", "togglePlayPause"), fake.calls);
    }

    @Test
    public void readoutDependsOnTransportCapabilityNotTheWholeLyricsHost() throws Exception {
        Field hostField = TrackInfoReadoutController.class.getDeclaredField("host");
        assertEquals(PlaybackTransportHost.class, hostField.getType());

        boolean constructorBindsCapability = false;
        for (Constructor<?> constructor : TrackInfoReadoutController.class.getDeclaredConstructors()) {
            for (Class<?> parameter : constructor.getParameterTypes()) {
                assertNotSame(LyricsHost.class, parameter);
                if (parameter == PlaybackTransportHost.class) constructorBindsCapability = true;
            }
        }
        assertTrue("the readout constructor must accept the transport capability",
                constructorBindsCapability);

        Method attach = null;
        for (Method method : TrackInfoReadoutController.class.getDeclaredMethods()) {
            if (!"attach".equals(method.getName())) continue;
            attach = method;
            assertTrue(Modifier.isStatic(method.getModifiers()));
            boolean attachBindsCapability = false;
            for (Class<?> parameter : method.getParameterTypes()) {
                assertNotSame(LyricsHost.class, parameter);
                if (parameter == PlaybackTransportHost.class) attachBindsCapability = true;
            }
            assertTrue("attach must accept the transport capability", attachBindsCapability);
        }
        assertNotNull("attach factory must exist", attach);
    }

    private static final class RecordingTransport implements PlaybackTransportHost {
        final List<String> calls = new ArrayList<>();
        boolean result = true;

        @Override
        public boolean togglePlayPause() {
            calls.add("togglePlayPause");
            return result;
        }

        @Override
        public boolean skipToNextTrack() {
            calls.add("skipToNextTrack");
            return result;
        }

        @Override
        public boolean skipToPreviousTrack() {
            calls.add("skipToPreviousTrack");
            return result;
        }
    }
}
