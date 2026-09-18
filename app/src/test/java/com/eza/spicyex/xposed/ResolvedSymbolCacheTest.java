package com.eza.spicyex.xposed;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class ResolvedSymbolCacheTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    public static final class Fixture {
        public final Object value() { return null; }
        public final Object value(String[] input, int count) { return input; }
        public final boolean present() { return true; }
    }

    public static final class SynchronizedFixture {
        public final synchronized boolean present() { return true; }
        public final synchronized Object value() { return null; }
        public final Object value(String input) { return input; }
    }

    @Test public void runtimeTrackLookupPreservesDexKitContainsModifierMatching() throws Exception {
        assertEquals(SynchronizedFixture.class.getDeclaredMethod("present"),
                SpotifySymbolResolver.findTrackMethod(SynchronizedFixture.class, boolean.class));
        assertEquals(SynchronizedFixture.class.getDeclaredMethod("value"),
                SpotifySymbolResolver.findTrackMethod(SynchronizedFixture.class, Object.class));
    }

    private ResolvedSymbolCache cache(File file, String identity) {
        return new ResolvedSymbolCache(file, identity, getClass().getClassLoader());
    }

    @Test public void restartReusesMethodsClassesAndSuccessfulEmptyProbes() throws Exception {
        File file = new File(temporary.getRoot(), "cache");
        Method method = Fixture.class.getDeclaredMethod("value", String[].class, int.class);
        ResolvedSymbolCache cold = cache(file, "apk1:resolver1");
        cold.method("track", () -> method);
        cold.classes("lyrics", () -> Arrays.asList(Fixture.class.getName()));
        cold.classes("absent", Collections::emptyList);
        ResolvedSymbolCache warm = cache(file, "apk1:resolver1");
        assertEquals(method, warm.method("track", () -> { throw new AssertionError("rescanned"); }));
        assertEquals(Collections.singletonList(Fixture.class), warm.classes("lyrics", () -> {
            throw new AssertionError("rescanned");
        }));
        assertTrue(warm.classes("absent", () -> { throw new AssertionError("rescanned empty"); }).isEmpty());
        assertEquals("hits=3 misses=0", warm.stats());
    }

    @Test public void changedApkOrResolverInvalidatesRecords() throws Exception {
        File file = new File(temporary.getRoot(), "cache");
        AtomicInteger calls = new AtomicInteger();
        for (String identity : Arrays.asList("apk1:r1", "apk2:r1", "apk2:r2")) {
            cache(file, identity).classes("lyrics", () -> {
                calls.incrementAndGet();
                return Collections.emptyList();
            });
        }
        assertEquals(3, calls.get());
    }

    @Test public void invalidClassAndMethodRecordsFallBackAndAreRepaired() throws Exception {
        File file = new File(temporary.getRoot(), "cache");
        Properties records = new Properties();
        records.setProperty("identity", "same");
        records.setProperty("classes.lyrics", "missing.Class");
        Method method = Fixture.class.getDeclaredMethod("value");
        // A valid owner and name with a wrong return type must not resolve by name alone.
        records.setProperty("method.track", ResolvedSymbolCache.signature(method).replace("java.lang.Object", "int"));
        try (FileOutputStream output = new FileOutputStream(file)) { records.store(output, null); }
        ResolvedSymbolCache symbols = cache(file, "same");
        assertEquals(method, symbols.method("track", () -> method));
        assertEquals(Collections.singletonList(Fixture.class), symbols.classes("lyrics",
                () -> Collections.singletonList(Fixture.class.getName())));
        assertEquals("hits=0 misses=2", symbols.stats());
        assertEquals(method, cache(file, "same").method("track", () -> { throw new AssertionError(); }));
    }

    @Test public void failedScanAndPartialClassLoadingAreNotCached() throws Exception {
        File file = new File(temporary.getRoot(), "cache");
        ResolvedSymbolCache symbols = cache(file, "same");
        try {
            symbols.classes("failed", () -> { throw new IllegalStateException("native unavailable"); });
            fail();
        } catch (IllegalStateException expected) { }
        assertEquals(Collections.singletonList(Fixture.class), symbols.classes("partial",
                () -> Arrays.asList("missing.Class", Fixture.class.getName())));
        ResolvedSymbolCache restarted = cache(file, "same");
        AtomicInteger calls = new AtomicInteger();
        for (String key : Arrays.asList("failed", "partial")) {
            restarted.classes(key, () -> { calls.incrementAndGet(); return Collections.emptyList(); });
        }
        assertEquals(2, calls.get());
    }

    @Test public void corruptOrUnwritableStorageDoesNotBlockDiscovery() throws Exception {
        File file = new File(temporary.getRoot(), "cache");
        java.nio.file.Files.write(file.toPath(), "bad=\\uXXXX".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertTrue(cache(file, "same").classes("lyrics", Collections::emptyList).isEmpty());
        File impossible = new File(file, "not-a-directory/cache");
        Method method = Fixture.class.getDeclaredMethod("present");
        assertEquals(method, cache(impossible, "same").method("track", () -> method));
    }
}
