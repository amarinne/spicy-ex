package com.eza.spicyex.settings.editor;

import com.eza.spicyex.Settings;
import com.eza.spicyex.settings.registry.ControlSpec;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Locale;

import static org.junit.Assert.*;

/** The layout editor's list mode: order, headers, selection scroll, scroll retention, card refusal. */
public class EditorListPlanTest {
    private static final String CONTROLLER =
            "src/main/java/com/eza/spicyex/hooks/LyricsLayoutEditController.java";
    private static final int[] TOPS = {0, 400, 900, 2000, 2600, 3000, 3400};

    @Test public void listHasTheFixedOrderAndOneHeaderPerElement() {
        assertEquals(Arrays.asList("artwork", "track_text", "lyrics", "focus", "background",
                "top_bar", "skip_follow"), EditorListPlan.sectionIds());
        EnumSet<ControlSpec.Element> seen = EnumSet.noneOf(ControlSpec.Element.class);
        for (EditorListPlan.Section section : EditorListPlan.SECTIONS) {
            assertTrue("two headers for " + section.element, seen.add(section.element));
        }
        // Every element except the card, which keeps its own list in the card editor.
        assertEquals(EnumSet.complementOf(EnumSet.of(ControlSpec.Element.CARD)), seen);
    }

    @Test public void headersReuseTheExistingElementLabels() throws Exception {
        for (String locale : new String[]{"values", "values-ja", "values-ko"}) {
            String strings = read("src/main/res/" + locale + "/strings.xml");
            for (EditorListPlan.Section section : EditorListPlan.SECTIONS) {
                assertTrue(locale + " " + section.labelKey, strings.contains(
                        "<string name=\"settings_layout_editor_" + section.labelKey + "\">"));
            }
        }
    }

    @Test public void modeSwitchWritesNoSetting() throws Exception {
        EditorListPlan.State state = new EditorListPlan.State();
        assertTrue(state.set(EditorListPlan.Mode.LIST, false));
        assertTrue(state.set(EditorListPlan.Mode.ELEMENT, false));
        // No stored setting carries the panel mode.
        for (Settings.Setting<?> setting : Settings.ALL) {
            String key = setting.key.toLowerCase(Locale.ROOT);
            assertFalse(key, key.contains("list_mode") || key.contains("editor_mode"));
        }
        // The mode switch path holds no write: not in the plan, the list views, or the session.
        for (String path : new String[]{
                "src/main/java/com/eza/spicyex/settings/editor/EditorListPlan.java",
                "src/main/java/com/eza/spicyex/settings/editor/EditorListPanel.java"}) {
            String source = read(path);
            assertFalse(path, source.contains("writer") || source.contains("put("));
        }
        String controller = read(CONTROLLER);
        for (String name : new String[]{"setPanelMode", "togglePanelMode"}) {
            String body = EditorCompositesSourceTest.method(controller, name);
            assertFalse(body, body.contains("put(") || body.contains("writer"));
        }
    }

    @Test public void editorOpensPerElementEvenAfterAListSession() throws Exception {
        EditorListPlan.State previous = new EditorListPlan.State();
        previous.set(EditorListPlan.Mode.LIST, false);
        assertEquals(EditorListPlan.Mode.ELEMENT, new EditorListPlan.State().mode());
        // Each session owns a fresh state; nothing static carries the mode to the next open.
        assertTrue(read(CONTROLLER).contains(
                "private final EditorListPlan.State panelMode = new EditorListPlan.State();"));
    }

    @Test public void selectionScrollsToTheSectionAndAnEditKeepsTheScroll() {
        EditorListPlan.State state = new EditorListPlan.State();
        state.set(EditorListPlan.Mode.LIST, false);
        assertEquals(2000, state.scrollTo(ControlSpec.Element.FOCUS, TOPS));
        assertEquals("focus", idAt(state.scrollY()));
        assertEquals(3400, state.scrollTo(ControlSpec.Element.CHIPS, TOPS));
        assertEquals("skip_follow", idAt(state.scrollY()));
        assertEquals(900, state.scrollTo(ControlSpec.Element.LYRICS, TOPS));

        // The user scrolls inside the Lyrics section, then edits a row: the rebuild restores it.
        state.rememberScroll(1200);
        assertEquals(1200, state.scrollY());
        assertEquals("lyrics", idAt(state.scrollY()));

        // The card has no section and moves nothing.
        assertEquals(-1, EditorListPlan.indexOf(ControlSpec.Element.CARD));
        assertEquals(1200, state.scrollTo(ControlSpec.Element.CARD, TOPS));
        assertEquals(-1, EditorListPlan.firstVisible(new int[0], 0));
    }

    @Test public void theToolbarToggleAlwaysOpensTheListWhileThePanelIsHidden() {
        // Hidden, the toggle opens the full list whatever mode the hidden panel was left in.
        assertEquals(EditorListPlan.Mode.LIST,
                EditorListPlan.toggled(EditorListPlan.Mode.ELEMENT, false));
        assertEquals(EditorListPlan.Mode.LIST,
                EditorListPlan.toggled(EditorListPlan.Mode.LIST, false));
        // Visible, it swaps the two modes.
        assertEquals(EditorListPlan.Mode.LIST,
                EditorListPlan.toggled(EditorListPlan.Mode.ELEMENT, true));
        assertEquals(EditorListPlan.Mode.ELEMENT,
                EditorListPlan.toggled(EditorListPlan.Mode.LIST, true));
        // The mode the toggle picks is the one the session then shows.
        EditorListPlan.State state = new EditorListPlan.State();
        assertTrue(state.set(EditorListPlan.toggled(state.mode(), true), false));
        assertEquals(EditorListPlan.Mode.LIST, state.mode());
        state.set(EditorListPlan.toggled(state.mode(), true), false);
        assertEquals(EditorListPlan.Mode.ELEMENT, state.mode());
    }

    @Test public void theCardEditorHasNoListToggle() {
        assertNull(EditorListPlan.toggleRefusal(true, false));
        assertEquals("editor is not open", EditorListPlan.toggleRefusal(false, false));
        assertEquals("the card editor has no list toggle",
                EditorListPlan.toggleRefusal(true, true));
    }

    @Test public void cardModeRefusesList() {
        assertNull(EditorListPlan.refusal(false, "list"));
        assertNull(EditorListPlan.refusal(false, "element"));
        assertNull(EditorListPlan.refusal(true, "element"));
        assertEquals("editor mode 'list' is not a mode in card mode; expected element",
                EditorListPlan.refusal(true, "list"));
        assertEquals("editor mode 'grid' is not a mode in lyrics mode; expected element, list",
                EditorListPlan.refusal(false, "grid"));

        EditorListPlan.State state = new EditorListPlan.State();
        assertFalse(state.set(EditorListPlan.Mode.LIST, true));
        assertEquals(EditorListPlan.Mode.ELEMENT, state.mode());
        assertEquals(EditorListPlan.Mode.LIST, EditorListPlan.Mode.parse(" LIST "));
        assertNull(EditorListPlan.Mode.parse("grid"));
    }

    private static String idAt(int scrollY) {
        return EditorListPlan.SECTIONS.get(EditorListPlan.firstVisible(TOPS, scrollY)).id;
    }

    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }
}
