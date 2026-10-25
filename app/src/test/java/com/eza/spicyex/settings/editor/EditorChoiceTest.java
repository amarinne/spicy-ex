package com.eza.spicyex.settings.editor;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The tag a choice chip carries is the contract between the row that renders it and the route
 * that picks it, and the refusal rules are what keep a request from writing anything it must
 * not write. Both sides are pinned here.
 */
public class EditorChoiceTest {

    @Test
    public void theTagNamesTheSettingAndTheValue() {
        assertEquals("option.lyrics_skip_chip_style.Icon",
                EditorRows.choiceTag("lyrics_skip_chip_style", "Icon"));
    }

    @Test
    public void theTagKeepsTheValueCase() {
        // The stored value is what the panel shows and what the route looks up: a request
        // that lowers the case names a choice that does not exist.
        assertEquals("option.lyrics_track_info_position.Top",
                EditorRows.choiceTag("lyrics_track_info_position", "Top"));
        assertNotEquals(EditorRows.choiceTag("lyrics_track_info_position", "Top"),
                EditorRows.choiceTag("lyrics_track_info_position", "top"));
    }

    @Test
    public void oneSettingsPrefixNeverReachesAnother() {
        // "lyrics_font" must not reach "lyrics_font_size": the prefix ends in a dot.
        assertFalse(EditorRows.choiceTagPrefix("lyrics_font")
                .startsWith(EditorRows.choiceTagPrefix("lyrics_font_size")));
        assertTrue(EditorRows.choiceTag("lyrics_font_size", "Regular")
                .startsWith(EditorRows.choiceTagPrefix("lyrics_font_size")));
        assertFalse(EditorRows.choiceTag("lyrics_font_size", "Regular")
                .startsWith(EditorRows.choiceTagPrefix("lyrics_font")));
    }

    @Test
    public void aRequestNeedsBothASettingAndAValue() {
        assertNull(EditorChoice.of(null, "Icon"));
        assertNull(EditorChoice.of("", "Icon"));
        assertNull(EditorChoice.of("lyrics_skip_chip_style", null));
        assertNull(EditorChoice.of("lyrics_skip_chip_style", ""));
        EditorChoice choice = EditorChoice.of("lyrics_skip_chip_style", "Icon");
        assertEquals("lyrics_skip_chip_style", choice.settingKey());
        assertEquals("Icon", choice.value());
    }

    @Test
    public void aClosedEditorRefusesTheRequest() {
        EditorChoice choice = EditorChoice.of("lyrics_skip_chip_style", "Icon");
        assertEquals("editor is not open", choice.refusal(false, false,
                Collections.singletonList("Icon")));
        assertEquals("editor is not open", choice.refusal(false, true,
                Collections.singletonList("Icon")));
    }

    @Test
    public void aClosedPanelRefusesTheRequest() {
        EditorChoice choice = EditorChoice.of("lyrics_skip_chip_style", "Icon");
        assertEquals("the options panel is closed", choice.refusal(true, false,
                Collections.singletonList("Icon")));
    }

    @Test
    public void aSettingThePanelDoesNotRenderIsRefused() {
        // The panel is the only source of truth: a setting the current layout does not offer
        // has no chip, so there is nothing to pick and nothing is written.
        EditorChoice choice = EditorChoice.of("lyrics_track_info_text_align", "Top");
        assertEquals("no rendered choice for 'lyrics_track_info_text_align'",
                choice.refusal(true, true, Collections.<String>emptyList()));
        assertEquals("no rendered choice for 'lyrics_track_info_text_align'",
                choice.refusal(true, true, null));
    }

    @Test
    public void aValueTheSettingDoesNotHaveIsRefused() {
        EditorChoice choice = EditorChoice.of("lyrics_skip_chip_style", "Icon");
        assertEquals("'Icon' is not a choice of 'lyrics_skip_chip_style'",
                choice.refusal(true, true, Arrays.asList("Auto", "Label")));
    }

    @Test
    public void theCaseOfTheValueIsWhatDecides() {
        EditorChoice choice = EditorChoice.of("lyrics_skip_chip_style", "icon");
        assertEquals("'icon' is not a choice of 'lyrics_skip_chip_style'",
                choice.refusal(true, true, Arrays.asList("Auto", "Label", "Icon")));
    }

    @Test
    public void aRenderedChoiceIsAccepted() {
        EditorChoice choice = EditorChoice.of("lyrics_skip_chip_style", "Icon");
        assertNull(choice.refusal(true, true, Arrays.asList("Auto", "Label", "Icon")));
    }
}
