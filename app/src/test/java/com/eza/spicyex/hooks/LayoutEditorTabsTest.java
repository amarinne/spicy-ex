package com.eza.spicyex.hooks;

import com.eza.spicyex.settings.registry.ControlSpec;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * The editor's tab strips are the one place a probe and a user can disagree about what a tab is,
 * so the names each mode accepts - and the names it refuses - are pinned here.
 */
public class LayoutEditorTabsTest {

    @Test public void cardEditorTakesTextAndAnimationOnly() {
        assertEquals(0, LayoutEditorTabs.index(true, "text"));
        assertEquals(1, LayoutEditorTabs.index(true, "animation"));
        assertEquals(-1, LayoutEditorTabs.index(true, "style"));
        assertEquals(-1, LayoutEditorTabs.index(true, "effects"));
    }

    @Test public void lyricsEditorTakesStyleAnimationAndEffectsOnly() {
        assertEquals(0, LayoutEditorTabs.index(false, "style"));
        assertEquals(1, LayoutEditorTabs.index(false, "animation"));
        assertEquals(2, LayoutEditorTabs.index(false, "effects"));
        assertEquals(-1, LayoutEditorTabs.index(false, "text"));
    }

    @Test public void anUnknownNameIsRefusedInBothModes() {
        assertEquals(-1, LayoutEditorTabs.index(true, "bogus"));
        assertEquals(-1, LayoutEditorTabs.index(false, "bogus"));
        assertEquals(-1, LayoutEditorTabs.index(true, null));
        assertEquals(-1, LayoutEditorTabs.index(false, ""));
    }

    @Test public void tabNamesAreCaseAndSpaceInsensitive() {
        assertEquals(1, LayoutEditorTabs.index(true, "Animation"));
        assertEquals(1, LayoutEditorTabs.index(false, " ANIMATION "));
    }

    @Test public void theRefusalNamesTheOpenModeAndItsTabs() {
        assertEquals("editor tab 'effects' is not a tab in card mode; expected text, animation",
                LayoutEditorTabs.refusal(true, "effects"));
        assertEquals("editor tab 'text' is not a tab in lyrics mode; expected style, animation, effects",
                LayoutEditorTabs.refusal(false, "text"));
        assertEquals("editor tab 'bogus' is not a tab in card mode; expected text, animation",
                LayoutEditorTabs.refusal(true, "bogus"));
    }

    /** A tab's index is the registry's tab index, so the strip order and the rows under it
     *  cannot drift apart. */
    @Test public void stripLengthsMatchTheRegistryTabCounts() {
        assertEquals(ControlSpec.tabCount(ControlSpec.Element.LYRICS), LayoutEditorTabs.LYRICS.length);
        assertEquals(ControlSpec.tabCount(ControlSpec.Element.CARD), LayoutEditorTabs.CARD.length);
    }

    @Test public void theProbeReportsTheOpenTabOfEachStrip() {
        assertEquals("style", LayoutEditorTabs.name(false, 0));
        assertEquals("effects", LayoutEditorTabs.name(false, 2));
        assertEquals("text", LayoutEditorTabs.name(true, 0));
        assertEquals("animation", LayoutEditorTabs.name(true, 1));
        assertEquals("", LayoutEditorTabs.name(true, 2));
        assertEquals("", LayoutEditorTabs.name(false, -1));
    }
}
