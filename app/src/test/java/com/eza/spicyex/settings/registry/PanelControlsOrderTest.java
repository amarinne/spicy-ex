package com.eza.spicyex.settings.registry;

import com.eza.spicyex.Settings;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Guards the panel's explicit order against drift.
 *
 * <p>The panel used to inherit row order from {@code Settings.ALL} declaration order, so adding
 * a setting anywhere silently changed the panel. The order is now data in the control registry;
 * these tests fail if that data stops matching the renderable setting set exactly.
 */
public class PanelControlsOrderTest {
    /**
     * Every setting a user can see is described exactly once: in the panel's inventory or in the
     * editor's, never in both and never in neither.
     */
    @Test
    public void everyRenderableSettingIsDescribedExactlyOnce() {
        Set<Settings.Setting<?>> panelOwned = new HashSet<>();
        for (ControlSpec entry : ControlRegistry.panelControls()) {
            assertEquals(ControlSpec.Surface.PANEL, entry.surface);
            assertNotNull("a panel row declares its section", entry.section);
            if (entry.setting == null) continue; // an action row stores nothing
            assertTrue("duplicate row in the panel registry: " + entry.setting.key,
                    panelOwned.add(entry.setting));
        }
        Set<Settings.Setting<?>> editorOwned = new HashSet<>();
        for (ControlSpec entry : ControlRegistry.editorControls()) {
            if (entry.setting != null) editorOwned.add(entry.setting);
            if (entry.customValue != null) editorOwned.add(entry.customValue);
        }
        int renderable = 0;
        Set<Settings.Setting<?>> described = new HashSet<>(panelOwned);
        for (Settings.Setting<?> setting : Settings.ALL) {
            if (setting.section == Settings.INTERNAL) continue;
            renderable++;
            boolean inPanel = panelOwned.contains(setting);
            boolean inEditor = editorOwned.contains(setting);
            assertTrue(setting.key + " must have a registry entry", inPanel || inEditor);
            assertFalse(setting.key + " must not be described on two surfaces",
                    inPanel && inEditor);
            if (inEditor) described.add(setting);
        }
        assertEquals("every renderable setting is a panel row or an editor row", renderable,
                described.size());
    }

    @Test
    public void panelSectionsCoverEveryPanelRow() {
        int counted = 0;
        for (Settings.Section section : ControlRegistry.panelSections()) {
            counted += ControlRegistry.panelControls(section).size();
        }
        assertEquals(ControlRegistry.panelControls().size(), counted);
    }

    @Test
    public void internalSettingsNeverRenderInThePanel() {
        for (ControlSpec entry : ControlRegistry.panelControls()) {
            assertFalse("internal setting leaked into the panel: " + entry.rowId(),
                    entry.setting != null && entry.setting.section == Settings.INTERNAL);
        }
    }

    /** The panel's rows are grouped by section, and each section keeps its rows contiguous. */
    @Test
    public void eachSectionKeepsItsRowsContiguous() {
        List<Settings.Section> seen = new ArrayList<>();
        for (ControlSpec entry : ControlRegistry.panelControls()) {
            if (seen.isEmpty() || seen.get(seen.size() - 1) != entry.section) {
                seen.add(entry.section);
            }
        }
        assertEquals(seen.size(), new HashSet<>(seen).size());
        assertEquals(ControlRegistry.panelSections(), seen);
    }
}
