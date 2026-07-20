package com.eza.spicyex.hooks;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LyricsSessionPolicyTest {
    @Test
    public void rejectsResultAfterTrackGenerationChanges() {
        LyricsSessionPolicy policy = new LyricsSessionPolicy();
        policy.adoptTrack("spotify:track:old");
        int oldGeneration = policy.generation();

        policy.adoptTrack("spotify:track:new");

        assertFalse(policy.accepts(oldGeneration, "spotify:track:old"));
        assertTrue(policy.accepts(policy.generation(), "spotify:track:new"));
    }

    @Test
    public void sameTrackDoesNotAdvanceGeneration() {
        LyricsSessionPolicy policy = new LyricsSessionPolicy();
        policy.adoptTrack("spotify:track:same");
        int generation = policy.generation();

        assertFalse(policy.adoptTrack("spotify:track:same"));
        assertTrue(policy.accepts(generation, "spotify:track:same"));
    }

    @Test
    public void backgroundDemandCanBeRemovedWithoutChangingTrackIdentity() {
        LyricsSessionPolicy policy = new LyricsSessionPolicy();
        policy.adoptTrack("spotify:track:playing");
        int generation = policy.generation();

        policy.setBackgroundDemand(true);
        assertTrue(policy.hasBackgroundDemand());
        policy.setBackgroundDemand(false);

        assertFalse(policy.hasBackgroundDemand());
        assertTrue(policy.accepts(generation, "spotify:track:playing"));
    }
}
