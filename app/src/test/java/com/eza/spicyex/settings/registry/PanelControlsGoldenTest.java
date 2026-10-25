package com.eza.spicyex.settings.registry;

import com.eza.spicyex.Settings;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;

/**
 * Compares the registry's panel projection with the rows the panel rendered before the registry
 * existed.
 *
 * <p>This gate does not load the old builders or the old policy, so it survives their deletion.
 * It pins, per representative snapshot, the sections the panel showed, the rows it showed in
 * order, each row's kind, the values a selector offers, and the hand-built row that owns a
 * composite. A row's label follows its key (the panel resolves it as
 * {@code settings_label_<key>}), so the pinned key pins the label too.
 */
public class PanelControlsGoldenTest {
    @Test public void panelRowsMatchTheRecordedRowsInEverySnapshot() throws Exception {
        PanelControlsFixture fixture = PanelControlsFixture.read();
        assertEquals("the panel's section order", fixture.sections, sectionIds());
        for (PanelControlsFixture.Snapshot snapshot : fixture.snapshots) {
            UiContext context = new UiContext(false, false, false,
                    new SettingsSnapshot(snapshot.values), snapshot.languagePackReady);
            assertEquals(snapshot.name + ": sections", snapshot.renderedSections,
                    renderedSections(context));
            assertEquals(snapshot.name + ": rows", snapshot.rows, rows(context));
        }
    }

    /** The recorded snapshots cover the cases the panel's guards decide. */
    @Test public void theFixtureCoversTheDecidingSnapshots() throws Exception {
        PanelControlsFixture fixture = PanelControlsFixture.read();
        List<String> names = new ArrayList<>();
        for (PanelControlsFixture.Snapshot snapshot : fixture.snapshots) names.add(snapshot.name);
        assertEquals(java.util.Arrays.asList("defaults", "ai_off", "ai_on", "ai_deepseek",
                "apple_style", "sources", "translation_on", "transliteration_on", "furigana",
                "auto_resume_on", "pip_on", "auto_on", "everything_on", "language_pack_missing",
                "no_pack_transliteration_on", "no_pack_furigana", "ai_off_deepseek"), names);
    }

    /**
     * A stored DeepSeek provider with the AI switch off must not bring the DeepSeek reasoning
     * row back. The recorded snapshot carries the provider, so the golden fails if the guard
     * loses its AI-switch half.
     */
    @Test public void aDeepSeekProviderWithoutTheAiSwitchShowsNoReasoningRow() throws Exception {
        PanelControlsFixture fixture = PanelControlsFixture.read();
        PanelControlsFixture.Snapshot snapshot = null;
        for (PanelControlsFixture.Snapshot candidate : fixture.snapshots) {
            if ("ai_off_deepseek".equals(candidate.name)) snapshot = candidate;
        }
        assertNotNull("the fixture must record the DeepSeek provider with AI off", snapshot);
        assertEquals(Boolean.FALSE, snapshot.values.get(Settings.AI_ENABLED.key));
        assertEquals("deepseek", snapshot.values.get(Settings.AI_PROVIDER.key));
        assertFalse("the DeepSeek reasoning row must not render while AI is off",
                snapshot.rows.contains("ai/" + Settings.AI_DEEPSEEK_REASONING.key
                        + "|CHOICE|Off;Low;High;Max"));
        UiContext context = new UiContext(false, false, false,
                new SettingsSnapshot(snapshot.values), snapshot.languagePackReady);
        for (ControlSpec entry : ControlRegistry.visiblePanelControls(context)) {
            assertNotSame("the DeepSeek reasoning row must not render while AI is off",
                    Settings.AI_DEEPSEEK_REASONING, entry.setting);
        }
    }

    private static List<String> sectionIds() {
        List<String> ids = new ArrayList<>();
        for (Settings.Section section : ControlRegistry.panelSections()) ids.add(section.id);
        return ids;
    }

    private static List<String> renderedSections(UiContext context) {
        List<String> ids = new ArrayList<>();
        for (Settings.Section section : ControlRegistry.visiblePanelSections(context)) {
            ids.add(section.id);
        }
        return ids;
    }

    /** The visible rows as {@code section/row|KIND|detail}. */
    private static List<String> rows(UiContext context) {
        List<String> rows = new ArrayList<>();
        for (ControlSpec entry : ControlRegistry.visiblePanelControls(context)) {
            rows.add(entry.section.id + "/" + entry.rowId() + "|"
                    + entry.kind.name() + "|" + detail(entry));
        }
        return rows;
    }

    private static String detail(ControlSpec entry) {
        if (entry.kind == ControlSpec.Kind.COMPOSITE) return entry.compositeId;
        if (entry.kind != ControlSpec.Kind.CHOICE) return "";
        Settings.StringSetting setting = (Settings.StringSetting) entry.setting;
        return setting.allowedValues == null
                ? "@dynamic" : String.join(";", setting.allowedValues);
    }
}
