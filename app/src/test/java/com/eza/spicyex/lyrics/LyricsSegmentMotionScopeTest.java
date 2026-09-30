package com.eza.spicyex.lyrics;

import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * F6: Apple motion constants are scoped per segment. Two renderers with different Apple
 * configurations step their own segments side by side — the artwork overlay with lift on,
 * the card with lift off — and each keeps its own spring identity and velocity instead of
 * resetting the other's every frame.
 */
public class LyricsSegmentMotionScopeTest {
    private static SyllableSegment segment() {
        return new SyllableSegment();
    }

    /** Step a fresh spring from rest toward a step goal and report an early trajectory sample. */
    private static float trajectory(SyllableSegment segment, boolean appleMotion) {
        LyricsSyllableViewState.syncSegmentMotion(segment, appleMotion);
        LyricsSyllableViewState.stepWordY(segment, 0f, 1f / 60f);
        float sample = 0f;
        for (int i = 0; i < 5; i++) {
            sample = LyricsSyllableViewState.stepWordY(segment, 10f, 1f / 60f);
        }
        return sample;
    }

    @Test
    public void simultaneousSegmentsKeepTheirOwnMotionConstants() {
        SyllableSegment artworkWord = segment();
        SyllableSegment cardWord = segment();

        // Interleaved exactly like simultaneous frames: sync and step artwork (lift on),
        // then sync and step the card (lift off), repeatedly.
        float artworkSample = 0f;
        float cardSample = 0f;
        for (int frame = 0; frame < 5; frame++) {
            LyricsSyllableViewState.syncSegmentMotion(artworkWord, true);
            artworkSample = LyricsSyllableViewState.stepWordY(artworkWord,
                    frame == 0 ? 0f : 10f, 1f / 60f);
            LyricsSyllableViewState.syncSegmentMotion(cardWord, false);
            cardSample = LyricsSyllableViewState.stepWordY(cardWord,
                    frame == 0 ? 0f : 10f, 1f / 60f);
        }

        assertNotEquals("artwork (lift) and card (plain) springs must diverge",
                artworkSample, cardSample, 0.0001f);
        assertTrue("both springs move toward the goal",
                artworkSample > 0f && cardSample > 0f);
    }

    @Test
    public void motionConstantFollowsTheSegmentsOwnSync() {
        SyllableSegment word = segment();

        float plain = trajectory(word, false);
        float lifted = trajectory(segment(), true);

        assertNotEquals("the same stepping must answer each segment's own sync",
                plain, lifted, 0.0001f);
    }
}
