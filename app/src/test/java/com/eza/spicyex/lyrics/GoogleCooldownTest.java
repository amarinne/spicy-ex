package com.eza.spicyex.lyrics;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

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

    @Test
    public void parsesOnlyTheSecondsForm() {
        assertEquals(0L, GoogleCooldown.parseRetryAfterMs(null));
        assertEquals(0L, GoogleCooldown.parseRetryAfterMs("Wed, 21 Oct 2026 07:28:00 GMT"));
        assertEquals(0L, GoogleCooldown.parseRetryAfterMs("0"));
        assertEquals(5_000L, GoogleCooldown.parseRetryAfterMs("5"));
    }
}
