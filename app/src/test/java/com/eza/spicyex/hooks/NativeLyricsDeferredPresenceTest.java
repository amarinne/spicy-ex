package com.eza.spicyex.hooks;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/** Deferred hook installs when a missing class ships in the APK, even if not loadable yet. */
public class NativeLyricsDeferredPresenceTest {
    @Test
    public void emptyMissingInstallsNothing() {
        AtomicInteger probes = new AtomicInteger();

        boolean install = callAnyMissing(Collections.emptyList(), name -> {
            probes.incrementAndGet();
            return true;
        });

        assertFalse(install);
        assertTrue(probes.get() == 0);
    }

    @Test
    public void skipsOnlyWhenNoMissingShips() {
        boolean install = callAnyMissing(
                Arrays.asList("com.spotify.lyrics.a.Lyrics", "com.spotify.lyrics.a.ColorLyrics"),
                name -> false);

        assertFalse(install);
    }

    @Test
    public void presentButNotLoadableStillInstalls() {
        // Presence is a DexKit name match, never a class load: the probe returns true without
        // calling Class.forName, modelling a class that ships but is not loadable yet.
        boolean install = callAnyMissing(
                Collections.singletonList("com.spotify.lyrics.serviceretrofit.proto.v3.LyricsV3Response"),
                name -> true);

        assertTrue(install);
    }

    @Test
    public void anyShippedNameInstalls() {
        AtomicInteger probes = new AtomicInteger();
        boolean install = callAnyMissing(
                Arrays.asList("com.spotify.lyrics.missing.One", "com.spotify.lyrics.missing.Two"),
                name -> {
                    probes.incrementAndGet();
                    return name.endsWith("Two");
                });

        assertTrue(install);
        assertTrue(probes.get() == 2);
    }

    @Test
    public void dexKitErrorPropagatesForFailSafeInstall() {
        try {
            callAnyMissing(
                    Collections.singletonList("com.spotify.lyrics.missing.One"),
                    name -> {
                        throw new IllegalStateException("DexKit unavailable");
                    });
            fail("probe failure must propagate so the caller fails safe to installing the hook");
        } catch (RuntimeException expected) {
            assertTrue(expected.getMessage().contains("DexKit"));
        }
    }

    private static boolean callAnyMissing(java.util.List<String> missing,
            NativeLyricsCaptureHook.DeferredPresenceProbe probe) {
        try {
            return NativeLyricsCaptureHook.anyMissingShipsInApk(missing, probe);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
