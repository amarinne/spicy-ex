package com.eza.spicyex.lyrics;

import org.junit.Test;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class JapaneseReadingEngineTest {
    @Test
    public void trimMemoryReleasesResources() throws Exception {
        JapaneseReadingEngine engine = new JapaneseReadingEngine();
        assertFalse(engine.jmdictPreferredReadings().isEmpty());

        engine.trimMemory();

        assertNull(field("jmdictPreferredReadings").get(engine));
        assertNull(field("jmdictFurigana").get(engine));
        assertNull(field("tokenizer").get(engine));
    }

    @Test
    public void trimDuringActiveUseDefersReleaseAndAvoidsReload() throws Exception {
        JapaneseReadingEngine engine = new JapaneseReadingEngine();
        engine.beginUse();
        Map<String, String> first = engine.jmdictPreferredReadings();
        assertFalse(first.isEmpty());

        engine.trimMemory();

        // The active use keeps the resident table: no reload while work is in flight.
        assertSame(first, engine.jmdictPreferredReadings());

        engine.endUse();

        assertNull(field("jmdictPreferredReadings").get(engine));
        assertNotSame(first, engine.jmdictPreferredReadings());
    }

    @Test
    public void memoryTrimDuringJapaneseProcessingDoesNotReloadResources() {
        JapaneseReadingEngine engine = JapaneseReadingEngine.shared();
        engine.beginUse();
        try {
            SpicyJapaneseChineseProcessor.romanizeJapaneseLine("君のことが好き");
            int loads = engine.tokenizerLoadsForTest();

            SpicyJapaneseChineseProcessor.trimMemory();
            SpicyJapaneseChineseProcessor.romanizeJapaneseLine("君のことが好き");

            assertEquals(loads, engine.tokenizerLoadsForTest());
        } finally {
            engine.endUse();
        }
    }

    /**
     * Normal local processing reaches Japanese analysis through the boundary-aware overload, so
     * that path must hold the resource lease without any manual acquisition.
     */
    @Test
    public void localRomanizerJapaneseAnalysisHoldsTheResourceLease() {
        JapaneseReadingEngine engine = JapaneseReadingEngine.shared();
        java.util.concurrent.atomic.AtomicInteger activeAtTokenize =
                new java.util.concurrent.atomic.AtomicInteger(-1);
        engine.tokenizerObserverForTest =
                () -> activeAtTokenize.set(engine.activeUsesForTest());
        try {
            LyricsDocument doc = new LyricsDocument();
            doc.language = "ja";
            LyricsLine line = new LyricsLine();
            line.text = "君のことが好き";
            doc.lines.add(line);

            LyricsLocalRomanizer.romanizeLine(RomanizationOptions.DEFAULTS, doc, line, line.text);
        } finally {
            engine.tokenizerObserverForTest = null;
        }

        assertTrue(activeAtTokenize.get() > 0);
    }

    /**
     * The bootstrap hook hands this an Application base context that has no application context
     * yet. Losing it would put the tokenizer on the packaged dictionaries the on-demand pack
     * replaced, so an uncached now-playing reading must still reach the downloaded models.
     */
    @Test
    public void attachContextKeepsTheSuppliedContextWhenThereIsNoApplicationContext() throws Exception {
        Field appContext = field("appContext");
        Object previouslyAttached = appContext.get(null);
        android.content.Context bootstrap = bootstrapWithoutApplicationContext();
        try {
            JapaneseReadingEngine.attachContext(bootstrap);

            assertSame(bootstrap, appContext.get(null));
        } finally {
            appContext.set(null, previouslyAttached);
        }
    }

    @Test
    public void attachContextPrefersTheApplicationContextWhenOneExists() throws Exception {
        Field appContext = field("appContext");
        Object previouslyAttached = appContext.get(null);
        final android.content.Context application = new android.content.ContextWrapper(null);
        android.content.Context bootstrap = new android.content.ContextWrapper(null) {
            @Override
            public android.content.Context getApplicationContext() {
                return application;
            }
        };
        try {
            JapaneseReadingEngine.attachContext(bootstrap);

            assertSame(application, appContext.get(null));
        } finally {
            appContext.set(null, previouslyAttached);
        }
    }

    @Test
    public void attachContextIgnoresNullSoAnUnrelatedHookCannotDropTheModels() throws Exception {
        Field appContext = field("appContext");
        Object previouslyAttached = appContext.get(null);
        android.content.Context bootstrap = bootstrapWithoutApplicationContext();
        try {
            JapaneseReadingEngine.attachContext(bootstrap);
            JapaneseReadingEngine.attachContext(null);

            assertSame(bootstrap, appContext.get(null));
        } finally {
            appContext.set(null, previouslyAttached);
        }
    }

    /** The bootstrap-hook shape: a base context that cannot offer an application context. */
    private static android.content.Context bootstrapWithoutApplicationContext() {
        return new android.content.ContextWrapper(null) {
            @Override
            public android.content.Context getApplicationContext() {
                return null;
            }
        };
    }

    private static Field field(String name) throws Exception {
        Field field = JapaneseReadingEngine.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
