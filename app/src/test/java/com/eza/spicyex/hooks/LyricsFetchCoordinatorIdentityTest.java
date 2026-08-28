package com.eza.spicyex.hooks;

import com.eza.spicyex.SpotifyTrack;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Packet M3: in-flight fetch identity must be bound to the non-secret token generation (never a
 * token-present boolean or token text), and the auth-rejection recovery seam must hand back a
 * replacement only when a strictly newer generation exists.
 */
public class LyricsFetchCoordinatorIdentityTest {
    private static final long NOW = 1_000_000L;
    private static final String SECRET = "secret-token-text-never-in-keys";

    private static SpotifyTrack track(String uri) {
        return new SpotifyTrack("Title", "Artist", "Album", uri, 0L, null, NOW, null, 0L, false);
    }

    private static SpotifyTokenState.Authorized authorized(String token) {
        SpotifyTokenState state = new SpotifyTokenState();
        assertTrue(state.capture(token, NOW, 0L));
        SpotifyTokenState.Authorized snapshot = state.authorization(NOW);
        assertNotNull(snapshot);
        return snapshot;
    }

    @Test
    public void sameGenerationSharesInFlightIdentity() {
        SpotifyTokenState.Authorized first = authorized(SECRET);
        SpotifyTokenState.Authorized second = authorized(SECRET);

        assertEquals(
                LyricsFetchCoordinator.fetchKey(null, true, first),
                LyricsFetchCoordinator.fetchKey(null, true, second));
    }

    @Test
    public void freshGenerationNeverJoinsStaleGenerationKey() {
        SpotifyTokenState state = new SpotifyTokenState();
        assertTrue(state.capture("stale-token-value", NOW, 0L));
        SpotifyTokenState.Authorized stale = state.authorization(NOW);
        assertNotNull(stale);
        assertTrue(state.capture("fresh-token-value", NOW + 1L, 0L));
        SpotifyTokenState.Authorized fresh = state.authorization(NOW + 2L);
        assertNotNull(fresh);
        assertTrue(fresh.generation() > stale.generation());

        // Guard: with the old token-present boolean both snapshots keyed identically and a fresh
        // token could join the stale in-flight request. Generations must separate them.
        assertNotEquals(
                LyricsFetchCoordinator.fetchKey(null, true, stale),
                LyricsFetchCoordinator.fetchKey(null, true, fresh));
    }

    @Test
    public void tokenlessIdentityIsSharedAndGenerationFree() {
        SpotifyTokenState.Authorized authorized = authorized(SECRET);

        String expected = "spotify:track:abc|tokenGen=none";
        assertEquals(expected, LyricsFetchCoordinator.fetchKey(track("spotify:track:abc"), false, authorized));
        assertEquals(expected, LyricsFetchCoordinator.fetchKey(track("spotify:track:abc"), true, null));
    }

    @Test
    public void inFlightKeyNeverContainsTokenText() {
        SpotifyTokenState.Authorized authorized = authorized(SECRET);
        SpotifyTokenState.Authorized fresh = authorized("other-secret-value");

        String key = LyricsFetchCoordinator.fetchKey(track("spotify:track:abc"), true, authorized);
        assertTrue(key.contains("spotify:track:abc"));
        assertTrue(key.contains("tokenGen=" + authorized.generation()));
        assertFalse(key.contains(SECRET));
        assertFalse(key.contains("other-secret-value"));
        assertFalse(LyricsFetchCoordinator.fetchKey(null, true, fresh).contains("other-secret-value"));
    }

    @Test
    public void retryReplacementOnlyWhenStrictlyNewerGenerationExists() {
        SpotifyTokenState state = new SpotifyTokenState();
        assertTrue(state.capture("stale-token-value", NOW, 0L));
        int staleGeneration = state.generation();
        SpotifyTokenState.Authorized stale = state.authorization(NOW);
        assertNotNull(stale);

        // No newer generation exists (token store tombstoned or unchanged): no retry candidate.
        assertNull(LyricsFetchCoordinator.retryAuthorizationAfterRejection(null, staleGeneration));
        assertNull(LyricsFetchCoordinator.retryAuthorizationAfterRejection(stale, staleGeneration));

        // A newer generation already exists: retry once against it.
        assertTrue(state.capture("fresh-token-value", NOW + 1L, 0L));
        SpotifyTokenState.Authorized fresh = state.authorization(NOW + 2L);
        assertNotNull(fresh);
        assertSame(
                fresh,
                LyricsFetchCoordinator.retryAuthorizationAfterRejection(fresh, staleGeneration));
    }

    @Test
    public void staleGenerationInvalidationCannotClobberNewerTokenEpoch() {
        SpotifyTokenState state = new SpotifyTokenState();
        assertTrue(state.capture("rejected-token-value", NOW, 0L));
        int rejectedGeneration = state.generation();

        // A fresher capture supersedes the rejected epoch before invalidation lands.
        assertTrue(state.capture("newer-token-value", NOW + 1L, 0L));
        int newerGeneration = state.generation();

        // The generation used by the rejected request is the only one that may be tombstoned;
        // a stale invalidation must not clear the newer token.
        assertFalse(state.invalidate(rejectedGeneration));
        assertNotNull(state.authorization(NOW + 2L));
        assertEquals(newerGeneration, state.generation());

        // Invalidating exactly the current generation tombstones it, and the tombstoned epoch
        // cannot be resurrected by a duplicate capture (no 401 retry loop).
        assertTrue(state.invalidate(newerGeneration));
        assertNull(state.authorization(NOW + 2L));
        assertFalse(state.capture("newer-token-value", NOW + 3L, 0L));
        assertNull(state.authorization(NOW + 3L));
    }

    private static void assertSame(Object expected, Object actual) {
        assertTrue("expected identical instances", expected == actual);
    }
}
