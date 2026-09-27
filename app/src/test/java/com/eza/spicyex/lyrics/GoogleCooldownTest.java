package com.eza.spicyex.lyrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The shared 429 backoff, on its own: nothing here opens a socket, so the timing the endpoint
 * forces on both lanes can be asserted exactly.
 */
public class GoogleCooldownTest {
    @Test
    public void backsOffAndDoublesOnRepeatedLimits() {
        GoogleCooldown cooldown = new GoogleCooldown();
        assertEquals(0L, cooldown.remainingMs(1_000L));

        cooldown.onRateLimited(1_000L, 0L);
        assertEquals(GoogleCooldown.BASE_MS, cooldown.remainingMs(1_000L));

        long later = 1_000L + GoogleCooldown.BASE_MS;
        assertEquals(0L, cooldown.remainingMs(later));
        cooldown.onRateLimited(later, 0L);
        assertEquals(2 * GoogleCooldown.BASE_MS, cooldown.remainingMs(later));
    }

    @Test
    public void capsAtTheMaximum() {
        GoogleCooldown cooldown = new GoogleCooldown();
        long now = 0L;
        for (int i = 0; i < 20; i++) {
            now += GoogleCooldown.MAX_MS;
            cooldown.onRateLimited(now, 0L);
        }
        assertEquals(GoogleCooldown.MAX_MS, cooldown.remainingMs(now));
    }

    @Test
    public void honoursRetryAfterAndSuccessResets() {
        GoogleCooldown cooldown = new GoogleCooldown();
        cooldown.onRateLimited(0L, GoogleCooldown.parseRetryAfterMs(" 120 "));
        assertEquals(120_000L, cooldown.remainingMs(0L));

        cooldown.onSuccess();
        assertEquals(0L, cooldown.remainingMs(0L));
        cooldown.onRateLimited(0L, 0L);
        assertEquals(GoogleCooldown.BASE_MS, cooldown.remainingMs(0L));
    }

    /**
     * A later 429 never shortens the wait, and a success is what makes the next ask immediate.
     * Both matter because the cooldown is process-wide: the Meaning lane's limit and the Sound
     * lane's answer share it.
     */
    @Test
    public void aFurtherLimitOnlyEverExtendsTheWait() {
        GoogleCooldown cooldown = new GoogleCooldown();
        cooldown.onRateLimited(0L, 120_000L);
        cooldown.onRateLimited(1_000L, 0L);
        assertEquals(120_000L, cooldown.remainingMs(0L));
        assertEquals(119_000L, cooldown.remainingMs(1_000L));
        assertEquals(118_000L, cooldown.remainingMs(2_000L));

        cooldown.onSuccess();
        assertEquals(0L, cooldown.remainingMs(2_000L));
    }

    @Test
    public void parsesOnlyTheSecondsForm() {
        assertEquals(0L, GoogleCooldown.parseRetryAfterMs(null));
        assertEquals(0L, GoogleCooldown.parseRetryAfterMs("Wed, 21 Oct 2026 07:28:00 GMT"));
        assertEquals(0L, GoogleCooldown.parseRetryAfterMs("0"));
        assertEquals(0L, GoogleCooldown.parseRetryAfterMs("-5"));
        assertEquals(5_000L, GoogleCooldown.parseRetryAfterMs("5"));
    }

    /** A server asking for longer than the ceiling is capped, never obeyed forever. */
    @Test
    public void retryAfterIsCappedLikeTheFallback() {
        GoogleCooldown cooldown = new GoogleCooldown();
        cooldown.onRateLimited(0L, GoogleCooldown.parseRetryAfterMs("3600"));
        assertEquals(GoogleCooldown.MAX_MS, cooldown.remainingMs(0L));
        assertFalse(cooldown.remainingMs(0L) > GoogleCooldown.MAX_MS);
        assertTrue(cooldown.remainingMs(GoogleCooldown.MAX_MS) == 0L);
    }
}
