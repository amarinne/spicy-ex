package com.eza.spicyex.settings;

import com.eza.spicyex.Settings;

/**
 * UI metadata for one setting: stable row identity, renderer kind, and commit policy.
 *
 * <p>Extends the {@link Settings} persistence schema (key/section/label/default/allowedValues)
 * with the presentation data the panel previously re-derived through per-key branches.
 * Composite workflows (source ordering, credentials, model discovery, diagnostics) are
 * {@link RowKind#COMPOSITE}: explicitly hand-built rows, never forced into ordinary metadata.
 */
public final class SettingUiSpec {
    public enum RowKind {
        TOGGLE,
        STEPPER,
        SINGLE_SELECT,
        TEXT_FIELD,
        COMPOSITE
    }

    public final Settings.Setting<?> setting;
    public final RowKind kind;
    public final CommitPolicy commitPolicy;
    /** Stable row identity across rebuilds; the persisted preference key. Never renamed. */
    public final String rowId;

    private SettingUiSpec(Settings.Setting<?> setting, RowKind kind, CommitPolicy commitPolicy) {
        this.setting = setting;
        this.kind = kind;
        this.commitPolicy = commitPolicy;
        this.rowId = setting.key;
    }

    static SettingUiSpec of(Settings.Setting<?> setting) {
        return new SettingUiSpec(setting, kindOf(setting), PanelPolicy.commitPolicyFor(setting));
    }

    static RowKind kindOf(Settings.Setting<?> setting) {
        if (setting == Settings.LYRICS_SOURCE_MODE
                || setting == Settings.LYRICS_SOURCE_OVERRIDE
                || setting == Settings.LYRICS_SOURCE_ORDER
                || setting == Settings.SPICY_MANUAL_TOKEN) {
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
