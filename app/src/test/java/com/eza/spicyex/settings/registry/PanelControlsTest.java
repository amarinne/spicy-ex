package com.eza.spicyex.settings.registry;

import com.eza.spicyex.Settings;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/** The panel's section order, row kinds and hand-built rows, stated as registry data. */
public class PanelControlsTest {
    @Test
    public void sectionOrderIsExplicit() {
        List<String> ids = new ArrayList<>();
        for (Settings.Section section : ControlRegistry.panelSections()) ids.add(section.id);
        assertEquals(Arrays.asList(
                "lyrics", "lyrics_sources", "lyrics_screen", "apple_music",
                "transliteration", "translation", "ai", "android_auto", "pip"), ids);
    }

    @Test
    public void kindsMatchRendererDispatch() {
        assertEquals(ControlSpec.Kind.TOGGLE, kindOf(Settings.TRANSLATION_ENABLED));
        assertEquals(ControlSpec.Kind.STEPPER, kindOf(Settings.SYNC_OFFSET_MS));
        assertEquals(ControlSpec.Kind.CHOICE, kindOf(Settings.TAP_SEEK_MODE));
        assertEquals(ControlSpec.Kind.CHOICE, kindOf(Settings.UI_LANGUAGE));
    }

    @Test
    public void compositesStayExplicit() {
        assertEquals("source_row", compositeOf(Settings.LYRICS_SOURCE_MODE));
        assertEquals("spicy_key", compositeOf(Settings.SPICY_ORG_CLIENT_KEY));
        assertNull(compositeOf(Settings.CACHE_SIZE));
        assertNull(compositeOf(Settings.AI_PROVIDER));
    }

    /** The merged source row owns the override and the order: they never render on their own. */
    @Test
    public void theMergedSourceRowOwnsTheOverrideAndTheOrder() {
        assertEquals("source_row", compositeOf(Settings.LYRICS_SOURCE_OVERRIDE));
        assertEquals("source_row", compositeOf(Settings.LYRICS_SOURCE_ORDER));
        for (Settings.Setting<?> setting : new Settings.Setting<?>[]{
                Settings.LYRICS_SOURCE_OVERRIDE, Settings.LYRICS_SOURCE_ORDER}) {
            assertEquals(setting.key + " must not render a row of its own",
                    Visibility.NEVER, visibleWhen(setting));
        }
    }

    /** A setting the layout editor owns has no panel row at all. */
    @Test
    public void editorOwnedSettingsHaveNoPanelRow() {
        assertNull(kindOf(Settings.ANIMATION_STYLE));
        assertNull(kindOf(Settings.FULLSCREEN_CONTROLS));
        assertNull(kindOf(Settings.TRACK_INFO_POSITION));
        assertNull(kindOf(Settings.FOLLOW_CHIP_ICON));
    }

    /** The language pack download is an action row: it stores nothing and names itself. */
    @Test
    public void theLanguagePackDownloadIsAnActionRowWithoutAStoredKey() {
        ControlSpec download = null;
        for (ControlSpec entry : ControlRegistry.panelControls()) {
            if ("download_language_models".equals(entry.compositeId)) download = entry;
        }
        assertNotNull("the language pack download must have a panel entry", download);
        assertNull("the download action stores nothing", download.setting);
        assertEquals(Settings.TRANSLITERATION, download.section);
    }

    private static ControlSpec entry(Settings.Setting<?> setting) {
        for (ControlSpec entry : ControlRegistry.panelControls()) {
            if (entry.setting == setting) return entry;
        }
        return null;
    }

    private static ControlSpec.Kind kindOf(Settings.Setting<?> setting) {
        ControlSpec entry = entry(setting);
        return entry == null ? null : entry.kind;
    }

    private static String compositeOf(Settings.Setting<?> setting) {
        ControlSpec entry = entry(setting);
        assertNotNull(setting.key + " must have a panel entry", entry);
        return entry.compositeId;
    }

    private static Visibility visibleWhen(Settings.Setting<?> setting) {
        ControlSpec entry = entry(setting);
        assertNotNull(setting.key + " must have a panel entry", entry);
        return entry.visibleWhen;
    }
}
