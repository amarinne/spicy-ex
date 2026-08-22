package com.eza.spicyex;

import org.junit.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;

import javax.xml.parsers.DocumentBuilderFactory;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class SettingsUiStringsContractTest {
    @Test
    public void everyPanelSettingHasDefaultResourceText() throws Exception {
        Set<String> names = defaultStringNames();
        assertTrue(names.contains("settings_locale_code"));
        assertTrue(names.contains("settings_locale_name"));
        String[] fixedPanelStrings = {
                "settings_unavailable_full_build",
                "settings_enable_transliteration",
                "settings_enable_translation",
                "settings_sync_offset_summary",
                "settings_panel_resize",
                "settings_panel_close",
                "settings_action_clear_translation_cache",
                "settings_action_clear_lyrics_cache",
                "settings_action_open_github",
                "settings_status_summary",
                "settings_diagnostic_source_chosen",
                "settings_diagnostic_candidates_seen",
                "settings_diagnostic_type_chosen",
                "settings_diagnostic_spicy_version_sent",
                "settings_diagnostic_spicy_latest_version",
                "settings_diagnostic_token_present",
                "settings_diagnostic_spicy_query_status",
                "settings_diagnostic_packed_payload",
                "settings_diagnostic_poison_result",
                "settings_diagnostic_cache_write",
                "settings_yes",
                "settings_no"
        };
        for (String name : fixedPanelStrings) assertTrue("Missing " + name, names.contains(name));
        String[] explicitAiModeLabels = {
                "settings_option_ai_translation_mode_on_demand",
                "settings_option_ai_translation_mode_always_use_ai",
                "settings_option_ai_translation_pipeline_ai_only",
                "settings_option_ai_translation_pipeline_google_draft",
                "settings_option_ai_pronunciation_mode_on_demand",
                "settings_option_ai_pronunciation_mode_always_use_ai",
                "settings_option_ai_pronunciation_source_layered",
                "settings_option_ai_pronunciation_source_ai_only"
        };
        for (String name : explicitAiModeLabels) {
            assertTrue("Missing " + name, names.contains(name));
        }
        Set<Settings.Section> sections = new HashSet<>();
        for (Settings.Setting<?> setting : Settings.ALL) {
            if (setting.section == Settings.INTERNAL) continue;
            sections.add(setting.section);
            assertTrue("Missing " + SettingsUiResourceNames.setting(setting),
                    names.contains(SettingsUiResourceNames.setting(setting)));
            if (setting instanceof Settings.StringSetting && setting.allowedValues != null) {
                for (Object value : setting.allowedValues) {
                    String specific = SettingsUiResourceNames.option(setting.key, String.valueOf(value));
                    String generic = SettingsUiResourceNames.option(String.valueOf(value));
                    assertTrue("Missing " + specific + " or " + generic,
                            names.contains(specific) || names.contains(generic));
                }
            }
        }
        sections.add(Settings.DEBUG);
        for (Settings.Section section : sections) {
            assertTrue("Missing " + SettingsUiResourceNames.section(section),
                    names.contains(SettingsUiResourceNames.section(section)));
        }
    }

    @Test
    public void resourceNameNormalizationIsStableForStoredValues() {
        assertTrue("settings_option_zh_tw".equals(SettingsUiResourceNames.option("zh-TW")));
        assertTrue("settings_option_left_to_right_sentence".equals(
                SettingsUiResourceNames.option("Left to right (sentence)")));
    }

    @Test
    public void contributionTemplateCoversSettingsAndDiagnosticsStrings() throws Exception {
        Set<String> expected = defaultStringNames();
        expected.addAll(stringNames("src/main/res/values/diagnostics.xml"));
        assertEquals(expected, stringNames("translation/strings-template.xml"));
    }

    @Test
    public void injectedLyricsShellNeverResolvesModuleIdsAgainstSpotifyResources() throws Exception {
        File source = new File("src/main/java/com/eza/spicyex/hooks/NativeSpicyShellViewImpl.java");
        if (!source.isFile()) source = new File("app/" + source.getPath());
        assertTrue(source.isFile());
        String java = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);

        assertFalse(java.contains("getString(R.string"));
        assertFalse(java.contains("Toast.makeText(activity, R.string"));
    }

    private static Set<String> defaultStringNames() throws Exception {
        return stringNames("src/main/res/values/strings.xml");
    }

    private static Set<String> stringNames(String path) throws Exception {
        File file = new File(path);
        if (!file.isFile()) file = new File("app/" + path);
        assertTrue("Default strings.xml not found from " + new File(".").getAbsolutePath(), file.isFile());
        NodeList strings = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
                .getElementsByTagName("string");
        Set<String> names = new HashSet<>();
        for (int i = 0; i < strings.getLength(); i++) {
            Element element = (Element) strings.item(i);
            names.add(element.getAttribute("name"));
        }
        return names;
    }
}
