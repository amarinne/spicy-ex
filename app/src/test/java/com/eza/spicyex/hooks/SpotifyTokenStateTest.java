package com.eza.spicyex.hooks;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class SpotifyTokenStateTest {
    private static final long NOW = 1_000_000L;

    @Test
    public void staleGenerationCannotInvalidateNewerToken() {
        SpotifyTokenState state = new SpotifyTokenState();
        assertTrue(state.capture("first-secret", NOW, 0L));
        int staleGeneration = state.generation();
        assertTrue(state.capture("second-secret", NOW + 1L, 0L));

        assertFalse(state.invalidate(staleGeneration));
        assertNotNull(state.authorization(NOW + 2L));
        assertEquals(staleGeneration + 1, state.generation());
    }

    @Test
    public void exactGenerationInvalidationTombstonesToken() {
        SpotifyTokenState state = new SpotifyTokenState();
        assertTrue(state.capture("private-value", NOW, 0L));

        assertTrue(state.invalidate(state.generation()));
        assertNull(state.authorization(NOW));
        assertFalse(state.invalidate(state.generation()));
    }

    @Test
    public void identicalHealthyCaptureKeepsGenerationAndRefreshesMetadata() {
        SpotifyTokenState state = new SpotifyTokenState();
        assertTrue(state.capture("same-private-value", NOW, NOW + 180_000L));
        int generation = state.generation();

        assertFalse(state.capture("same-private-value", NOW + 5_000L, NOW + 240_000L));
        assertEquals(generation, state.generation());
        assertEquals(NOW + 5_000L, state.capturedAtMillis());
        assertEquals(NOW + 240_000L, state.expiresAtMillis());
    }

    @Test
    public void expiredTokenIsNotAuthorized() {
        SpotifyTokenState state = new SpotifyTokenState();
        assertTrue(state.capture("expiring-private-value", NOW, NOW + 60_000L));
        assertNull(state.authorization(NOW));
    }

    @Test
    public void webApiTokenCannotBeDowngradedByLogin5Token() {
        SpotifyTokenState state = new SpotifyTokenState();
        assertTrue(state.capture("BQC_web_api_token", NOW, 0L));
        assertEquals("BQC_web_api_token", state.authorization(NOW).token());

        assertFalse(state.capture("BQB_login5_session_token", NOW + 1000L, 0L));
        assertEquals("BQC_web_api_token", state.authorization(NOW + 2000L).token());
    }

    @Test
    public void diagnosticsNeverContainTokenText() {
        SpotifyTokenState state = new SpotifyTokenState();
        String secret = "do-not-print-this-value";
        assertTrue(state.capture(secret, NOW, 0L));
        SpotifyTokenState.Authorized authorized = state.authorization(NOW);

        assertNotNull(authorized);
        assertFalse(state.toString().contains(secret));
        assertFalse(authorized.toString().contains(secret));
    }
}
