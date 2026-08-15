package com.eza.spicyex.hooks;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
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
    public void pollingDemandIsReferenceCountedWithoutChangingTrackIdentity() {
        LyricsSessionPolicy policy = new LyricsSessionPolicy();
        policy.adoptTrack("spotify:track:playing");
        int generation = policy.generation();

        assertTrue(policy.acquirePollingDemand());
        assertFalse(policy.acquirePollingDemand());
        assertEquals(2, policy.pollingDemandCount());
        assertFalse(policy.releasePollingDemand());
        assertTrue(policy.hasPollingDemand());
        assertTrue(policy.releasePollingDemand());

        assertFalse(policy.hasPollingDemand());
        assertTrue(policy.accepts(generation, "spotify:track:playing"));
    }

    @Test
    public void releasingAbsentDemandDoesNotUnderflow() {
        LyricsSessionPolicy policy = new LyricsSessionPolicy();

        assertFalse(policy.releasePollingDemand());
        assertEquals(0, policy.pollingDemandCount());
    }
}
