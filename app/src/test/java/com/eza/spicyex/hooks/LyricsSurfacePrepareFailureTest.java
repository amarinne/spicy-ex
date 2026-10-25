package com.eza.spicyex.hooks;

import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.lyrics.LyricsLine;
import com.eza.spicyex.lyrics.SyllableSegment;
import com.eza.spicyex.lyrics.language.JapaneseReadingEngine;
import com.eza.spicyex.lyrics.language.SpicyJapaneseChineseProcessor;

import org.junit.Test;

import java.util.AbstractList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Covers the main-thread fallback for preparation that threw on the lyrics IO worker.
 *
 * <p>The worker body is represented by the Japanese analysis and projection steps the surface runs,
 * so the tokenizer lease observation is the real counter. The fallback is the production handler
 * with a stub surface.
 */
public class LyricsSurfacePrepareFailureTest {
    @Test
    public void throwingWorkerEndsLoadingAndReleasesJapaneseLease() {
        JapaneseReadingEngine engine = JapaneseReadingEngine.shared();
        int baseline = engine.activeUsesForTest();
        AtomicInteger activeAtTokenize = new AtomicInteger(-1);
        LyricsSurfaceDocumentGate gate = new LyricsSurfaceDocumentGate();
        gate.start();
        LyricsSurfaceDocumentGate.Candidate candidate = gate.offer("track-a");
        StubSurface surface = new StubSurface("track-a");
        try {
            prepareJapaneseLine(engine, activeAtTokenize);
            fail("preparation should throw");
        } catch (IllegalStateException failure) {
            // The failing projection ran under the analysis lease, so the throw cannot be
            // attributed to a preparation that never reached the tokenizer.
            assertEquals(baseline + 1, activeAtTokenize.get());
            // The lease must already be back to baseline before the fallback runs on main.
            assertEquals(baseline, engine.activeUsesForTest());
            LyricsSurfacePrepareFailure.log(7, "track-a", failure);
            LyricsSurfacePrepareFailure.handle(gate, candidate, "track-a", surface);
        } finally {
            engine.setTokenizerObserverForTest(null);
        }

        assertEquals(baseline, engine.activeUsesForTest());
        assertTrue(surface.loadingEnded);
        assertEquals(1, surface.skeletonClears);
        assertEquals(1, surface.errors);
        assertEquals("[SpotifyPlusSpicy] prepare failed generation=7 track=track-a",
                LyricsSurfacePrepareFailure.logMessage(7, "track-a"));
    }

    @Test
    public void supersededCandidateFailureLeavesLoadingToNewerPreparation() {
        LyricsSurfaceDocumentGate gate = new LyricsSurfaceDocumentGate();
        gate.start();
        LyricsSurfaceDocumentGate.Candidate old = gate.offer("track-a");
        gate.offer("track-b");
        StubSurface surface = new StubSurface("track-b");

        LyricsSurfacePrepareFailure.handle(gate, old, "track-a", surface);

        assertFalse(surface.loadingEnded);
        assertEquals(0, surface.skeletonClears);
        assertEquals(0, surface.errors);
    }

    @Test
    public void failureForDisplayedTrackKeepsLyricsAndOnlyEndsLoading() {
        LyricsSurfaceDocumentGate gate = new LyricsSurfaceDocumentGate();
        gate.start();
        LyricsSurfaceDocumentGate.Candidate candidate = gate.offer("track-a");
        StubSurface surface = new StubSurface("track-a");
        surface.displayedTrackId = "track-a";
        surface.displayedLyrics = true;

        LyricsSurfacePrepareFailure.handle(gate, candidate, "track-a", surface);

        assertTrue(surface.loadingEnded);
        assertEquals(0, surface.skeletonClears);
        assertEquals(0, surface.errors);
    }

    private static void prepareJapaneseLine(JapaneseReadingEngine engine, AtomicInteger activeAtTokenize) {
        // Record the lease while the real analysis is tokenizing, then fail in the projection
        // that runs under the same lease. Both steps are the surface's segment preparation.
        engine.setTokenizerObserverForTest(() -> activeAtTokenize.set(engine.activeUsesForTest()));
        LyricsDocument doc = new LyricsDocument();
        doc.language = "ja";
        LyricsLine line = new LyricsLine();
        line.text = "君のことが好き";
        SyllableSegment segment = new SyllableSegment();
        segment.text = line.text;
        line.syllables.add(segment);
        doc.lines.add(line);
        SpicyJapaneseChineseProcessor.romanizeJapaneseSyllables(line.text,
                new AbstractList<String>() {
                    @Override public String get(int index) {
                        throw new IllegalStateException("projection failure");
                    }

                    @Override public int size() {
                        return 1;
                    }
                });
    }

    private static final class StubSurface implements LyricsSurfacePrepareFailure.Surface {
        final String currentTrackId;
        String displayedTrackId = "";
        boolean displayedLyrics;
        boolean loadingEnded;
        int skeletonClears;
        int errors;

        StubSurface(String currentTrackId) {
            this.currentTrackId = currentTrackId;
        }

        @Override public boolean isRunning() {
            return true;
        }

        @Override public String currentTrackId() {
            return currentTrackId;
        }

        @Override public String displayedTrackId() {
            return displayedTrackId;
        }

        @Override public boolean hasDisplayedLyrics() {
            return displayedLyrics;
        }

        @Override public void endLoading() {
            loadingEnded = true;
        }

        @Override public void clearSkeleton() {
            skeletonClears++;
        }

        @Override public void showError() {
            errors++;
        }
    }
}
