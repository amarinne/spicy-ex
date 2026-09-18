package com.eza.spicyex.lyrics;

import com.eza.spicyex.lyrics.session.DetectionResult;
import com.eza.spicyex.lyrics.session.DetectionStatus;
import org.junit.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class LanguageDetectorManagerTest {
    @Test public void oneCompactModelServesLatinAndCyrillicAndHan() {
        AtomicInteger creates = new AtomicInteger();
        LanguageDetectorManager manager = new LanguageDetectorManager(() -> {
            creates.incrementAndGet();
            return handle("en", new AtomicInteger());
        });
        manager.detect("some words"); manager.detect("привет"); manager.detect("漢字");
        assertEquals(1, creates.get());
        assertEquals(1, manager.residentFamilies());
    }

    @Test public void scriptsAndLowSignalDoNotLoadTheModel() {
        LanguageDetectorManager manager = new LanguageDetectorManager(() -> { throw new AssertionError(); });
        assertEquals("ja", manager.detect("こんにちは").language);
        assertEquals("ko", manager.detect("안녕").language);
        assertEquals("el", manager.detect("γεια").language);
        assertEquals(DetectionStatus.UNKNOWN, manager.detect("♪ 123").status);
        assertEquals(DetectionStatus.SCRIPT_ONLY, manager.detect("愛").status);
        assertEquals(0, manager.residentFamilies());
    }

    @Test public void terminalResultsReuseOnlyMatchingTextAndErrorsRetry() {
        AtomicInteger attempts = new AtomicInteger();
        LanguageDetectorManager manager = new LanguageDetectorManager(() -> {
            if (attempts.incrementAndGet() == 1) throw new IllegalStateException("fixture");
            return handle("en", new AtomicInteger());
        });
        DetectionResult failed = manager.detect("some text");
        assertEquals(DetectionStatus.ERROR, failed.status);
        assertEquals("en", manager.detect("some text", failed).language);
        DetectionResult unknown = DetectionResult.unknown("", "same text");
        long count = manager.detectionCount();
        assertSame(unknown, manager.detect("same text", unknown));
        assertEquals(count, manager.detectionCount());
        assertNotSame(unknown, manager.detect("changed text", unknown));
    }

    @Test public void trimDefersReleaseAndReacquireUsesTheActiveModel() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), finish = new CountDownLatch(1);
        AtomicInteger creates = new AtomicInteger(), unloads = new AtomicInteger(), calls = new AtomicInteger();
        LanguageDetectorManager manager = new LanguageDetectorManager(() -> {
            creates.incrementAndGet();
            return new LanguageDetectorManager.DetectorHandle() {
                public LanguageDetectorManager.Analyze analyze(String text) {
                    if (calls.incrementAndGet() == 1) {
                        entered.countDown();
                        try { if (!finish.await(5, TimeUnit.SECONDS)) throw new AssertionError("timeout"); }
                        catch (InterruptedException e) { throw new AssertionError(e); }
                    }
                    return new LanguageDetectorManager.Analyze("en", .9);
                }
                public void unload() { unloads.incrementAndGet(); }
            };
        });
        Thread active = new Thread(() -> manager.detect("first text"));
        active.start();
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            manager.trimMemory();
            assertEquals("en", manager.detect("second text").language);
            assertEquals(0, unloads.get());
            assertEquals(1, creates.get());
        } finally { finish.countDown(); active.join(5000); }
        assertFalse(active.isAlive());
        assertEquals(1, unloads.get());
        assertEquals(0, manager.residentFamilies());
        manager.detect("third text");
        assertEquals(2, creates.get());
    }

    @Test public void hanLabelsCollapseToRoutingFamilies() {
        assertEquals("zh", LanguageDetectorManager.normalizeHanLabel("yue"));
        assertEquals("zh", LanguageDetectorManager.normalizeHanLabel("cmn"));
        assertEquals("zh", LanguageDetectorManager.normalizeHanLabel("zh"));
        assertEquals("ja", LanguageDetectorManager.normalizeHanLabel("ja"));
        assertEquals("en", LanguageDetectorManager.normalizeHanLabel("en"));
    }

    private static LanguageDetectorManager.DetectorHandle handle(String language, AtomicInteger unloads) {
        return new LanguageDetectorManager.DetectorHandle() {
            public LanguageDetectorManager.Analyze analyze(String text) {
                return new LanguageDetectorManager.Analyze(language, .95);
            }
            public void unload() { unloads.incrementAndGet(); }
        };
    }
}
