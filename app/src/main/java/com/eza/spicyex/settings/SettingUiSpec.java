package com.eza.spicyex.settings;

import com.eza.spicyex.Settings;

/** Classifies settings for ordinary and explicitly composed row renderers. */
public final class SettingUiSpec {
    public enum RowKind {
        TOGGLE,
        STEPPER,
        SINGLE_SELECT,
        TEXT_FIELD,
        COMPOSITE
    }

    private SettingUiSpec() {}

    static RowKind kindOf(Settings.Setting<?> setting) {
        if (setting == Settings.LYRICS_SOURCE_MODE
                || setting == Settings.LYRICS_SOURCE_OVERRIDE
                || setting == Settings.LYRICS_SOURCE_ORDER
                || setting == Settings.SPICY_ORG_CLIENT_KEY
                || setting == Settings.DOWNLOAD_LANGUAGE_MODELS) {
            return RowKind.COMPOSITE;
        }
        if (setting instanceof Settings.BooleanSetting) return RowKind.TOGGLE;
        if (setting instanceof Settings.IntegerSetting) return RowKind.STEPPER;
        if (setting instanceof Settings.StringSetting) {
            // UI_LANGUAGE carries dynamic locale values instead of a static allowed list.
            if (setting == Settings.UI_LANGUAGE) return RowKind.SINGLE_SELECT;
            Settings.StringSetting string = (Settings.StringSetting) setting;
            if (string.allowedValues == null || string.allowedValues.isEmpty()) {
                return RowKind.TEXT_FIELD;
            }
            return RowKind.SINGLE_SELECT;
        }
        return RowKind.TEXT_FIELD;
    }
}
