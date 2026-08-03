package com.eza.spicyex;

import java.util.Locale;

/** Pure naming contract shared by settings resources and JVM tests. */
final class SettingsUiResourceNames {
    private SettingsUiResourceNames() {
    }

    static String section(Settings.Section section) {
        return "settings_section_" + normalize(section == null ? "" : section.id);
    }

    static String setting(Settings.Setting<?> setting) {
        return "settings_label_" + normalize(setting == null ? "" : setting.key);
    }

    static String option(String value) {
        return "settings_option_" + normalize(value);
    }

    static String option(String settingKey, String value) {
        return "settings_option_" + normalize(settingKey) + "_" + normalize(value);
    }

    static String normalize(String value) {
        String source = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(source.length());
        boolean separator = false;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                if (separator && out.length() > 0) out.append('_');
                out.append(c);
                separator = false;
            } else {
                separator = true;
            }
        }
        return out.toString();
    }
}
