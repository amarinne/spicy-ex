package com.eza.spicyex.lyrics.language;

import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.lyrics.LyricsLine;
import com.eza.spicyex.lyrics.SyllableSegment;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class JapaneseSurfacePreparationConcurrencyTest {
    @Test public void concurrentSurfaceSegmentsHoldIndependentLeasesDuringTrim() throws Exception {
        JapaneseReadingEngine engine = JapaneseReadingEngine.shared();
        String expected = prepareSegments();
        assertFalse(expected.isEmpty());
        int loads = engine.tokenizerLoadsForTest();
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        engine.tokenizerObserverForTest = () -> {
            entered.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("tokenizer wait timed out");
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new AssertionError(failure);
            }
        };
        try {
            Future<String> first = workers.submit(JapaneseSurfacePreparationConcurrencyTest::prepareSegments);
            Future<String> second = workers.submit(JapaneseSurfacePreparationConcurrencyTest::prepareSegments);
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            assertEquals(2, engine.activeUsesForTest());
            engine.trimMemory();
            assertEquals(loads, engine.tokenizerLoadsForTest());
            release.countDown();
            assertEquals(expected, first.get(10, TimeUnit.SECONDS));
            assertEquals(expected, second.get(10, TimeUnit.SECONDS));
            assertEquals(0, engine.activeUsesForTest());
            assertEquals(loads, engine.tokenizerLoadsForTest());
        } finally {
            release.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS));
            engine.tokenizerObserverForTest = null;
        }
    }

    private static String prepareSegments() {
        LyricsDocument doc = new LyricsDocument();
        doc.language = "ja";
        LyricsLine line = new LyricsLine();
        line.text = "君のことが好き";
        SyllableSegment segment = new SyllableSegment();
        segment.text = line.text;
        line.syllables.add(segment);
        doc.lines.add(line);
        LyricsLocalRomanizer.populateLocalSegmentRomanization(
                RomanizationOptions.DEFAULTS, doc, line, line.text);
        return segment.romanizedText;
    }
}
