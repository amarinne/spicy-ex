package com.eza.spicyex.hooks;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LyricsSessionLifecycleTest {
    @Test
    public void closeIsIdempotentAndPreventsLaterDelivery() {
        LyricsSessionLifecycle.HandleState state = new LyricsSessionLifecycle.HandleState();

        assertTrue(state.close());
        assertFalse(state.close());
        assertFalse(state.isActive());
        assertFalse(state.consume());
    }

    @Test
    public void consumingRequestIsOneShot() {
        LyricsSessionLifecycle.HandleState state = new LyricsSessionLifecycle.HandleState();

        assertTrue(state.consume());
        assertFalse(state.consume());
        assertFalse(state.isActive());
    }
}
