package com.eza.spicyex.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class PlaybackBridgeTest {
    @Test
    public void playerStateTakesPriorityOverBufferingSession() {
        PlaybackBridge bridge = new PlaybackBridge();
        assertEquals(Boolean.TRUE, bridge.playerStatePlaying(new State(true, false), false));
        assertEquals(Boolean.FALSE, bridge.playerStatePlaying(new State(true, true), true));
        assertEquals(Boolean.FALSE, bridge.playerStatePlaying(new State(false, false), true));
    }

    @Test
    public void unreadableAccessorsFallBackToSession() {
        PlaybackBridge bridge = new PlaybackBridge();
        assertNull(bridge.playerStatePlaying(new BrokenState(), false));
        assertEquals(Boolean.TRUE, bridge.playerStatePlaying(new BrokenState(), true));
    }

    private static final class State {
        private final boolean playing;
        private final boolean paused;

        State(boolean playing, boolean paused) {
            this.playing = playing;
            this.paused = paused;
        }

        public boolean isPlaying() { return playing; }
        public boolean isPaused() { return paused; }
    }

    private static final class BrokenState {
        public boolean isPlaying() { throw new IllegalStateException(); }
        public boolean isPaused() { throw new IllegalStateException(); }
    }
}
