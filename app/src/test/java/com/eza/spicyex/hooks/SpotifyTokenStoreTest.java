package com.eza.spicyex.hooks;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SpotifyTokenStoreTest {
    @Test
    public void unknownExpiryRestoreUsesThirtyMinuteCeiling() {
        long now = 10_000_000L;
        assertTrue(SpotifyTokenStore.isFreshForRestore(now, now - 30L * 60_000L, 0L));
        assertFalse(SpotifyTokenStore.isFreshForRestore(now, now - 30L * 60_000L - 1L, 0L));
        assertFalse(SpotifyTokenStore.isFreshForRestore(now, now + 1L, 0L));
    }

    @Test
    public void knownExpiryMustClearSafetyMargin() {
        long now = 10_000_000L;
        assertTrue(SpotifyTokenStore.isFreshForRestore(now, now - 1L, now + 60_001L));
        assertFalse(SpotifyTokenStore.isFreshForRestore(now, now - 1L, now + 60_000L));
    }
}
