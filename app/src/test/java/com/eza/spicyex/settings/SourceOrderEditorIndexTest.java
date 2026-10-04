package com.eza.spicyex.settings;

import static org.junit.Assert.assertEquals;
import com.eza.spicyex.lyrics.session.LyricsSourcePreferences.Source;
import org.junit.Test;
import java.util.Arrays;
import java.util.List;

/** Every configured provider, including Spicy Lyrics, is visible and can be reordered. */
public class SourceOrderEditorIndexTest {
    @Test public void spicyUsesItsVisiblePosition() {
        List<Source> order = Arrays.asList(Source.APPLE_MUSIC, Source.SPICY, Source.SPOTIFY);
        assertEquals(0, SourceOrderEditor.visibleInsertionToOrderIndex(order, 0));
        assertEquals(1, SourceOrderEditor.visibleInsertionToOrderIndex(order, 1));
        assertEquals(2, SourceOrderEditor.visibleInsertionToOrderIndex(order, 2));
        assertEquals(3, SourceOrderEditor.visibleInsertionToOrderIndex(order, 3));
    }
    @Test public void spicyAtTheHeadIsTheFirstDropTarget() {
        assertEquals(0, SourceOrderEditor.visibleInsertionToOrderIndex(
                Arrays.asList(Source.SPICY, Source.SPOTIFY), 0));
    }
}
