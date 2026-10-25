package com.eza.spicyex;

import com.eza.spicyex.testsupport.FakePrefs;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

public class PanelMediaMigrationTest {
    @Test
    public void storedTrueBecomesSingleTap() {
        FakePrefs prefs = new FakePrefs();
        prefs.values.put(Settings.PANEL_MEDIA_CONTROLS.key, true);
        SettingsStore.migratePanelMediaControls(prefs);
        assertEquals("Single tap", prefs.values.get(Settings.PANEL_MEDIA_CONTROLS.key));
    }

    @Test
    public void storedFalseBecomesOff() {
        FakePrefs prefs = new FakePrefs();
        prefs.values.put(Settings.PANEL_MEDIA_CONTROLS.key, false);
        SettingsStore.migratePanelMediaControls(prefs);
        assertEquals("Off", prefs.values.get(Settings.PANEL_MEDIA_CONTROLS.key));
    }

    @Test
    public void migratedStringPassesThrough() {
        FakePrefs prefs = new FakePrefs();
        prefs.values.put(Settings.PANEL_MEDIA_CONTROLS.key, "Double tap");
        SettingsStore.migratePanelMediaControls(prefs);
        assertEquals("Double tap", prefs.values.get(Settings.PANEL_MEDIA_CONTROLS.key));
    }

    @Test
    public void absentKeyWritesNothing() {
        FakePrefs prefs = new FakePrefs();
        SettingsStore.migratePanelMediaControls(prefs);
        assertFalse(prefs.values.containsKey(Settings.PANEL_MEDIA_CONTROLS.key));
    }
}
