package com.eza.spicyex.hooks;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LyricsActivityTakeoverBackPolicyTest {
    @Test
    public void interceptsBackWhileSessionActiveAcrossRotationWindow() {
        // Rotation/track-change window: session survives, root not yet remounted.
        assertTrue(LyricsActivityTakeoverHook.shouldInterceptLyricsBack(true, false));
        assertTrue(LyricsActivityTakeoverHook.shouldInterceptLyricsBack(true, true));
        assertTrue(LyricsActivityTakeoverHook.shouldInterceptLyricsBack(false, true));
    }

    @Test
    public void leavesInactiveNativeScreenUntouched() {
        assertFalse(LyricsActivityTakeoverHook.shouldInterceptLyricsBack(false, false));
    }

    @Test
    public void suppressesImplicitFinishWhileSessionActive() {
        assertTrue(LyricsActivityTakeoverHook.shouldSuppressLyricsFinish(true, true, true, false));
    }

    @Test
    public void explicitBackAlwaysExits() {
        assertFalse(LyricsActivityTakeoverHook.shouldSuppressLyricsFinish(true, true, true, true));
        assertFalse(LyricsActivityTakeoverHook.shouldSuppressLyricsFinish(false, true, true, true));
    }

    @Test
    public void noSuppressionWithoutSessionOrWhenStayOffOrNativeOff() {
        assertFalse(LyricsActivityTakeoverHook.shouldSuppressLyricsFinish(true, true, false, false));
        assertFalse(LyricsActivityTakeoverHook.shouldSuppressLyricsFinish(false, true, true, false));
        assertFalse(LyricsActivityTakeoverHook.shouldSuppressLyricsFinish(true, false, true, false));
    }
}
