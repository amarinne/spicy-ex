package com.eza.spicyex.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.eza.spicyex.Settings;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Guards the explicit UI order against drift.
 *
 * <p>The panel used to inherit row order from {@code Settings.ALL} declaration order, so adding
 * a setting anywhere silently changed the panel. Order is now data in {@link SettingsUiSchema};
 * these tests fail if that data stops matching the renderable setting set.
 */
public class SettingsUiSchemaOrderTest {
    @Test
    public void explicitOrderCoversEveryRenderableSettingExactlyOnce() {
        List<Settings.Setting<?>> rendered = new ArrayList<>();
        for (Settings.Setting<?> setting : Settings.ALL) {
            if (setting.section == Settings.INTERNAL) continue;
            rendered.add(setting);
        }
        List<Settings.Setting<?>> ordered = SettingsUiSchema.orderedSettings();

        Set<Settings.Setting<?>> seen = new HashSet<>();
        for (Settings.Setting<?> setting : ordered) {
            assertTrue("duplicate row in SettingsUiSchema: " + setting.key, seen.add(setting));
        }
        assertEquals("schema order must list every renderable setting once",
                new HashSet<>(rendered), seen);
        assertEquals("schema order must not omit or add rows", rendered.size(), ordered.size());
    }

    @Test
    public void orderedSectionsCoverEveryRenderableRow() {
        int counted = 0;
        for (Settings.Section section : SettingsUiSchema.orderedSections()) {
            counted += SettingsUiSchema.orderedSettings(section).size();
        }
        assertEquals(SettingsUiSchema.orderedSettings().size(), counted);
    }

    @Test
    public void internalSettingsNeverRender() {
        for (Settings.Setting<?> setting : SettingsUiSchema.orderedSettings()) {
            assertFalse("internal setting leaked into the panel: " + setting.key,
                    setting.section == Settings.INTERNAL);
        }
    }
}
