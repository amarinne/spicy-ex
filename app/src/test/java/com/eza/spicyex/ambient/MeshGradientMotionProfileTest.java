package com.eza.spicyex.ambient;

import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.lyrics.LyricsLine;
import com.eza.spicyex.lyrics.SyllableSegment;
import org.junit.Test;
import static org.junit.Assert.*;

public class MeshGradientMotionProfileTest {
    @Test public void seedUsesUriAndIgnoresPresentationFields() {
        MeshGradientMotionProfile first = MeshGradientMotionProfile.forTrack(
                "spotify:track:abc", "image-one", null);
        MeshGradientMotionProfile same = MeshGradientMotionProfile.forTrack(
                "spotify:track:abc", "image-two", null);
        assertEquals(first.seed, same.seed);
        assertEquals(first.phase, same.phase, 0f);
        assertTrue(first.phase >= 0f && first.phase < MeshGradientMotionProfile.TWO_PI);
    }

    @Test public void imageIdIsStableFallbackWhenUriIsMissing() {
        MeshGradientMotionProfile first = MeshGradientMotionProfile.forTrack(
                "", "image-one", null);
        MeshGradientMotionProfile same = MeshGradientMotionProfile.forTrack(
                null, "image-one", null);
        MeshGradientMotionProfile other = MeshGradientMotionProfile.forTrack(
                "", "image-two", null);
        assertEquals(first.seed, same.seed);
        assertNotEquals(first.seed, other.seed);
    }

    @Test public void missingTimingIsNeutral() {
        MeshGradientMotionProfile profile = MeshGradientMotionProfile.forTrack("track", "image", null);
        assertFalse(profile.hasTiming);
        assertEquals(MeshGradientMotionProfile.NEUTRAL_SPEED, profile.speed, 0f);
    }

    @Test public void wordTimingCountsWhenLineBoundsAreAbsent() {
        LyricsDocument document = new LyricsDocument();
        document.durationMs = 60_000L;
        LyricsLine line = line(0, 0, false);
        SyllableSegment word = new SyllableSegment();
        word.startMs = 1_000L;
        word.endMs = 2_000L;
        line.syllables.add(word);
        document.lines.add(line);
        MeshGradientMotionProfile profile = MeshGradientMotionProfile.forTrack("word", "", document);
        assertTrue(profile.hasTiming);
        assertTrue(profile.speed < MeshGradientMotionProfile.NEUTRAL_SPEED);
    }

    @Test public void interludeAndPauseSlowProfileWhileDenseTimingAccelerates() {
        LyricsDocument sparse = new LyricsDocument();
        sparse.durationMs = 120_000L;
        sparse.lines.add(line(0, 3_000, false));
        sparse.lines.add(line(3_000, 117_000, true));

        LyricsDocument dense = new LyricsDocument();
        dense.durationMs = 120_000L;
        for (int i = 0; i < 24; i++) dense.lines.add(line(i * 5_000L, i * 5_000L + 4_000L, false));

        MeshGradientMotionProfile slow = MeshGradientMotionProfile.forTrack("sparse", "", sparse);
        MeshGradientMotionProfile fast = MeshGradientMotionProfile.forTrack("dense", "", dense);
        assertTrue(slow.hasTiming);
        assertTrue(fast.hasTiming);
        assertTrue(slow.speed < MeshGradientMotionProfile.NEUTRAL_SPEED);
        assertTrue(fast.speed > MeshGradientMotionProfile.NEUTRAL_SPEED);
        assertTrue(slow.speed >= MeshGradientMotionProfile.MIN_SPEED);
        assertTrue(fast.speed <= MeshGradientMotionProfile.MAX_SPEED);
    }

    @Test public void everyTimingProfileStaysWithinConservativeBounds() {
        LyricsDocument document = new LyricsDocument();
        document.durationMs = 1_000L;
        document.lines.add(line(0, 1_000, false));
        MeshGradientMotionProfile profile = MeshGradientMotionProfile.forTrack("x", "", document);
        assertTrue(profile.speed >= 0.85f && profile.speed <= 1.25f);
    }

    @Test public void timingRateUsesEmaToAvoidASecondPublicationJump() {
        MeshGradientMotionProfile previous = MeshGradientMotionProfile.forTrack("old", "", null);
        LyricsDocument dense = new LyricsDocument();
        dense.durationMs = 1_000L;
        dense.lines.add(line(0, 1_000, false));
        MeshGradientMotionProfile target = MeshGradientMotionProfile.forTrack("new", "", dense);
        MeshGradientMotionProfile smoothed = target.smoothFrom(previous);
        assertTrue(smoothed.speed > previous.speed);
        assertTrue(smoothed.speed < target.speed);
        assertEquals(previous.speed + (target.speed - previous.speed)
                * MeshGradientMotionProfile.EMA_ALPHA, smoothed.speed, 0.000001f);
    }

    private static LyricsLine line(long start, long end, boolean interlude) {
        LyricsLine line = new LyricsLine();
        line.startMs = start;
        line.endMs = end;
        line.interlude = interlude;
        return line;
    }
}
