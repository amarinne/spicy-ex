package com.eza.spicyex.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.eza.spicyex.Settings;
import com.eza.spicyex.lyrics.session.LyricsSourcePreferences;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

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

    @Test
    public void editorChooseRequestNeedsAKeyAndAValue() {
        assertNull(AgentCommandChannel.editorChooseRequest(new String[]{"choose"}));
        assertNull(AgentCommandChannel.editorChooseRequest(new String[]{"choose", "lyrics_font"}));
        assertNull(AgentCommandChannel.editorChooseRequest(new String[]{"choose", "", "spotify"}));
        String[] request = AgentCommandChannel.editorChooseRequest(
                new String[]{"choose", "lyrics_font", "spotify"});
        assertEquals("lyrics_font", request[0]);
        assertEquals("spotify", request[1]);
    }

    @Test
    public void editorChooseRequestKeepsTheValueCaseAndItsSpaces() {
        // The value is the rest of the line, exactly as the panel stores it: a choice can
        // carry a space, and its case is part of the stored value.
        String[] request = AgentCommandChannel.editorChooseRequest(
                new String[]{"choose", "lyrics_background_style", "Static", "texture"});
        assertEquals("lyrics_background_style", request[0]);
        assertEquals("Static texture", request[1]);
        String[] cased = AgentCommandChannel.editorChooseRequest(
                new String[]{"choose", "lyrics_track_info_position", "Top"});
        assertEquals("Top", cased[1]);
    }

    @Test
    public void editorRandomRequestKeepsSearchTextAndRequiresOneToggleId() {
        assertEquals("open", AgentCommandChannel.editorRandomRequest(
                new String[]{"random", "open"})[0]);
        assertEquals("status", AgentCommandChannel.editorRandomRequest(
                new String[]{"random", "status"})[0]);
        String[] search = AgentCommandChannel.editorRandomRequest(
                new String[]{"random", "search", "mesh", "gradient"});
        assertEquals("search", search[0]);
        assertEquals("mesh gradient", search[1]);
        String[] cleared = AgentCommandChannel.editorRandomRequest(
                new String[]{"random", "search"});
        assertEquals("", cleared[1]);
        String[] toggle = AgentCommandChannel.editorRandomRequest(
                new String[]{"random", "toggle", "god-rays"});
        assertEquals("toggle", toggle[0]);
        assertEquals("god-rays", toggle[1]);
        assertNull(AgentCommandChannel.editorRandomRequest(new String[]{"random"}));
        assertNull(AgentCommandChannel.editorRandomRequest(new String[]{"random", "bogus"}));
        assertNull(AgentCommandChannel.editorRandomRequest(
                new String[]{"random", "open", "extra"}));
        assertNull(AgentCommandChannel.editorRandomRequest(
                new String[]{"random", "toggle"}));
        assertNull(AgentCommandChannel.editorRandomRequest(
                new String[]{"random", "toggle", "kawarp", "extra"}));
    }

    @Test
    public void theChooseRouteReachesTheRenderedControlItself() throws Exception {
        String editor = read("src/main/java/com/eza/spicyex/hooks/LyricsLayoutEditController.java");
        // The route picks the chip the panel rendered and lets that chip's own owner write:
        // no second writer, no synthetic rebuild, no tap on a coordinate.
        assertTrue(editor.contains("chip.performClick();"));
        assertTrue(editor.contains("rows.renderedChoices("));
        assertFalse(editor.contains("writer.put(settingKey"));
        assertFalse(editor.contains("writer.put(choice."));
    }

    private static String read(String path) throws Exception {
        File file = new File(path);
        if (!file.isFile()) file = new File("app/" + path);
        assertTrue(file.isFile());
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}
