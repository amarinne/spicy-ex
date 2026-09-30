package com.eza.spicyex.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.eza.spicyex.Settings;
import com.eza.spicyex.lyrics.session.LyricsSourcePreferences;
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

    @Test
    public void sourceModeWritesThroughTheOrdinaryStore() {
        // F12: the ranking label lives in the ordinary store, so the CLI may write it; the
        // channel mirrors it into the source namespace that CatalogPolicy.read consults.
        assertFalse(AgentCommandChannel.isAdapterOwnedSetting(Settings.LYRICS_SOURCE_MODE));
        assertEquals(LyricsSourcePreferences.RankingMode.SOURCE_ORDER,
                LyricsSourcePreferences.RankingMode.parse("Source order"));
        assertEquals(LyricsSourcePreferences.RankingMode.AUTO,
                LyricsSourcePreferences.RankingMode.parse("Auto"));
    }

    @Test
    public void adapterOwnedSourceKeysAreRefused() {
        // The override pin and the raw order string are committed only through
        // SourcePreferencesAdapter; a raw write would diverge the two namespaces.
        assertTrue(AgentCommandChannel.isAdapterOwnedSetting(Settings.LYRICS_SOURCE_OVERRIDE));
        assertTrue(AgentCommandChannel.isAdapterOwnedSetting(Settings.LYRICS_SOURCE_ORDER));
        assertFalse(AgentCommandChannel.isAdapterOwnedSetting(Settings.KARAOKE_ORIGINAL_LYRICS));
    }
}
