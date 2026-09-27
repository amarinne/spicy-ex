package com.eza.spicyex.lyrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Apple-style held-note swell: strength from duration, and the rise/hold/release envelope. */
public class HeldNoteEmphasisTest {

    @Test
    public void ordinaryWordsDoNotSwell() {
        // At or below the ratio floor there is no swell at all.
        assertEquals(0f, LyricAnimations.emphasisStrength(400, 400), 0f);
        assertEquals(0f, LyricAnimations.emphasisStrength(600, 500), 0f);
    }

    @Test
    public void aShortWordNeverSwellsEvenInAVeryFastLine() {
        // A rap verse whose average word is 100ms: a 300ms word is 3x the average but still far
        // too short to read as a held note, so the absolute floor suppresses it.
        assertEquals(0f, LyricAnimations.emphasisStrength(300, 100), 0f);
    }

    @Test
    public void strengthGrowsWithHowLongTheNoteIsHeld() {
        float modest = LyricAnimations.emphasisStrength(1000, 500);   // 2.0x
        float longer = LyricAnimations.emphasisStrength(1500, 500);   // 3.0x
        float full = LyricAnimations.emphasisStrength(2000, 500);     // 4.0x
        assertTrue(modest > 0f);
        assertTrue(longer > modest);
        assertEquals(1f, full, 0.0001f);
        // Beyond full ratio it stays capped rather than growing without bound.
        assertEquals(1f, LyricAnimations.emphasisStrength(8000, 500), 0.0001f);
    }

    @Test
    public void strengthIsRelativeToTheLineNotAbsolute() {
        // The same 1.2s word: a held note in a fast line, unremarkable in a slow one.
        assertTrue(LyricAnimations.emphasisStrength(1200, 300) > 0f);
        assertEquals(0f, LyricAnimations.emphasisStrength(1200, 1200), 0f);
    }

    @Test
    public void envelopeRisesHoldsThenReleases() {
        assertEquals(0f, LyricAnimations.emphasisEnvelope(0f), 0.0001f);
        assertEquals(1f, LyricAnimations.emphasisEnvelope(0.5f), 0.0001f);
        assertEquals(1f, LyricAnimations.emphasisEnvelope(0.7f), 0.0001f);
        assertEquals(0f, LyricAnimations.emphasisEnvelope(1f), 0.0001f);
        assertTrue(LyricAnimations.emphasisEnvelope(0.15f) > 0f);
        assertTrue(LyricAnimations.emphasisEnvelope(0.15f) < 1f);
        assertTrue(LyricAnimations.emphasisEnvelope(0.9f) < 1f);
    }

    @Test
    public void envelopeNeverDipsBelowRest() {
        // The old slow-word curve shrank the word to 0.96 of its size at 70% before recovering,
        // which read as a wobble. A held note only ever grows.
        for (int i = 0; i <= 100; i++) {
            float t = i / 100f;
            assertTrue("dipped at t=" + t, LyricAnimations.emphasisEnvelope(t) >= 0f);
            assertTrue("shrank at t=" + t, LyricAnimations.emphasisScale(t, 1f) >= 1f);
        }
    }

    @Test
    public void scaleTracksStrength() {
        assertEquals(1f, LyricAnimations.emphasisScale(0.5f, 0f), 0.0001f);
        assertEquals(1f + LyricAnimations.EMPHASIS_MAX_SCALE,
                LyricAnimations.emphasisScale(0.5f, 1f), 0.0001f);
        assertTrue(LyricAnimations.emphasisScale(0.5f, 1f)
                > LyricAnimations.emphasisScale(0.5f, 0.4f));
    }

    @Test
    public void liftIsUpwardAndProportional() {
        assertEquals(0f, LyricAnimations.emphasisLiftEm(0.5f, 0f), 0.0001f);
        assertEquals(-LyricAnimations.EMPHASIS_MAX_LIFT_EM,
                LyricAnimations.emphasisLiftEm(0.5f, 1f), 0.0001f);
        assertEquals(0f, LyricAnimations.emphasisLiftEm(1f, 1f), 0.0001f);
    }

    @Test
    public void lineLevelStrengthNeedsEnoughWordsToAverage() {
        AppliedLine twoWords = line(new long[][]{{0, 200}, {200, 3000}});
        assertEquals(0f, LyricsAnimationApplier.emphasisStrength(twoWords, 1), 0f);

        AppliedLine held = line(new long[][]{{0, 300}, {300, 600}, {600, 3000}});
        assertTrue(LyricsAnimationApplier.emphasisStrength(held, 2) > 0f);
        assertEquals(0f, LyricsAnimationApplier.emphasisStrength(held, 0), 0f);
    }

    @Test
    public void malformedWordsAreNeverEmphasised() {
        AppliedLine broken = line(new long[][]{{0, 300}, {300, 300}, {600, 3000}});
        assertEquals(0f, LyricsAnimationApplier.emphasisStrength(broken, 1), 0f);
        assertEquals(0f, LyricsAnimationApplier.emphasisStrength(null, 0), 0f);
        assertEquals(0f, LyricsAnimationApplier.emphasisStrength(broken, 99), 0f);
    }

    @Test
    public void wordScaleFoldsEmphasisOnTopOfTheOrdinaryCurve() {
        float plain = LyricsAnimationApplier.wordMotionScale(false, true, false, 0.5f, false, 0f);
        float swollen = LyricsAnimationApplier.wordMotionScale(false, true, false, 0.5f, false, 1f);
        assertTrue(swollen > plain);
        // Apple lift is vertical-only; held-note enlargement belongs to non-Apple letter motion.
        assertEquals(1f, LyricsAnimationApplier.wordMotionScale(true, true, false, 0.5f, false, 0f), 0.0001f);
        assertEquals(1f, LyricsAnimationApplier.wordMotionScale(true, true, false, 0.5f, false, 1f), 0.0001f);
    }

    private static AppliedLine line(long[][] spans) {
        AppliedLine line = new AppliedLine();
        for (long[] span : spans) {
            SyllableSegment seg = new SyllableSegment();
            seg.startMs = span[0];
            seg.endMs = span[1];
            seg.totalMs = Math.max(0, span[1] - span[0]);
            line.words.add(seg);
        }
        line.startMs = spans[0][0];
        line.endMs = spans[spans.length - 1][1];
        return line;
    }
}
