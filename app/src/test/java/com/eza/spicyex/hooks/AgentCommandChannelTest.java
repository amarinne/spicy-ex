package com.eza.spicyex.hooks;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class AgentCommandChannelTest {
    @Test
    public void footerReplyKeepsOnlyTheRenderedSourceLine() {
        assertEquals("Source: Spotify (through Musixmatch) · Line ›",
                AgentCommandChannel.firstLine(
                        "Source: Spotify (through Musixmatch) · Line ›\n"
                                + "lyrics provided by Spotify (through Musixmatch)"));
    }

    @Test
    public void missingFooterTextStaysEmpty() {
        assertEquals("", AgentCommandChannel.firstLine(null));
        assertEquals("", AgentCommandChannel.firstLine("   "));
    }
}
