package com.eza.spicyex.settings;

import static org.junit.Assert.assertEquals;

import com.eza.spicyex.lyrics.session.LyricsSourcePreferences.Source;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The reorder UI hides retired sources, so a visible drop position is not an index into the
 * full backing order. This mapping is the only thing keeping a drag from writing a wrong order,
 * and it had no test when the hidden-entry bug was fixed.
 */
public class SourceOrderEditorIndexTest {
    private static List<Source> order(Source... sources) {
        return new ArrayList<>(Arrays.asList(sources));
    }

    @Test
    public void visiblePositionSkipsTheHiddenRetiredEntry() {
        List<Source> backing = order(Source.APPLE_MUSIC, Source.SPICY, Source.SPOTIFY, Source.LRCLIB);
        assertEquals(0, SourceOrderEditor.visibleInsertionToOrderIndex(backing, 0));
        assertEquals(2, SourceOrderEditor.visibleInsertionToOrderIndex(backing, 1));
        assertEquals(3, SourceOrderEditor.visibleInsertionToOrderIndex(backing, 2));
    }

    @Test
    public void hiddenEntryAtTheHeadStillMapsCorrectly() {
        List<Source> backing = order(Source.SPICY, Source.SPOTIFY, Source.LRCLIB);
        assertEquals(1, SourceOrderEditor.visibleInsertionToOrderIndex(backing, 0));
        assertEquals(2, SourceOrderEditor.visibleInsertionToOrderIndex(backing, 1));
    }

    @Test
    public void positionPastTheLastVisibleItemLandsAtTheEnd() {
        List<Source> backing = order(Source.APPLE_MUSIC, Source.SPICY, Source.SPOTIFY);
        assertEquals(backing.size(), SourceOrderEditor.visibleInsertionToOrderIndex(backing, 2));
    }

    @Test
    public void noHiddenEntriesIsAnIdentityMapping() {
        List<Source> backing = order(Source.APPLE_MUSIC, Source.SPOTIFY, Source.LRCLIB);
        assertEquals(0, SourceOrderEditor.visibleInsertionToOrderIndex(backing, 0));
        assertEquals(1, SourceOrderEditor.visibleInsertionToOrderIndex(backing, 1));
        assertEquals(2, SourceOrderEditor.visibleInsertionToOrderIndex(backing, 2));
    }

    @Test
    public void everyHiddenEntryMapsToTheBackingSize() {
        List<Source> backing = order(Source.SPICY);
        assertEquals(1, SourceOrderEditor.visibleInsertionToOrderIndex(backing, 0));
    }
}
