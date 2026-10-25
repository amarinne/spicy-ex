package com.eza.spicyex.settings.editor;

import android.view.View;

import com.eza.spicyex.Settings;
import com.eza.spicyex.settings.registry.ControlRegistry;
import com.eza.spicyex.settings.registry.ControlSpec;
import com.eza.spicyex.settings.registry.UiContext;

import java.util.List;

/**
 * Renders one editor element's options panel from {@link ControlRegistry}: one registry entry per
 * row, in registry order, one group card per change of group title. An entry's {@code hintKey}
 * renders as a line inside its own row's card - below the control, or above it when the entry
 * asks for that - and its {@code labelKey} as a leading title, for a control that shares its card
 * with other rows.
 *
 * <p>A tabbed element renders one tab at a time and must name that tab; the untabbed call rejects
 * it rather than silently picking one. Untabbed elements render every row as before.
 *
 * <p>Each row's write applies the entry's {@link ControlSpec.Effect}: what the editor refreshes
 * after the value lands. Entries without a wired effect are a programming error, not a silent
 * no-op, so a later slice cannot forget one.
 */
public final class EditorControlRenderer {
    /** The editor session actions a migrated row can request. */
    public interface Host {
        /** Rebuilds the options panel for the element, keeping scroll and placement. */
        void select(ControlSpec.Element element);

        /**
         * Rebuilds the options panel of the element that is still selected. The skip and follow
         * chips share one panel, so a change to either of them must not move the selection.
         */
        void rebuildPanel();

        void refreshArtwork();

        void refreshTrackText();

        void refreshFocus();

        /** The background style changed: the panel rebuilds so its dependent rows follow, and
         *  the editor's capture outlines re-align once the ambient layer has settled. */
        void backgroundStyleChanged(ControlSpec.Element element);

        /** The top bar changed: the dock's capture and the editor's own toolbar re-place, and
         *  the panel rebuilds. */
        void chromeChanged();

        /** The skip and follow chips changed: both captures re-anchor and the panel rebuilds. */
        void chipsChanged();

        /** The now-playing card changed: its preview re-reads the stored configuration. */
        void cardChanged();

        /** The shell re-applies its stored settings: the row's value is one the shell reads
         *  as it draws, not as it lays out. */
        void applyPreferences();

        /** Opens the font chooser; a valid pick owns its write and the panel rebuild. */
        void chooseFont(View anchor);
    }

    private static final int INNER_ROW_GAP_DP = 6;
    private static final int HINT_TEXT_SP = 12;
    private static final int HINT_COLOR = 0x80FFFFFF;
    private static final int LABEL_TEXT_SP = 12;

    private final EditorRows rows;
    private final Host host;

    public EditorControlRenderer(EditorRows rows, Host host) {
        this.rows = rows;
        this.host = host;
    }

    /** Renders every row of an element without tabs. */
    public void render(ControlSpec.Element element, UiContext context) {
        if (ControlSpec.tabCount(element) > 0) {
            throw new IllegalArgumentException(element + " renders one tab at a time");
        }
        render(ControlRegistry.optionControls(element, context), context);
    }

    /** Renders the rows of one tab of a tabbed element. */
    public void render(ControlSpec.Element element, int tab, UiContext context) {
        render(ControlRegistry.tabControls(element, tab, context), context);
    }

    private void render(List<ControlSpec> controls, UiContext context) {
        String openGroup = null;
        for (int i = 0; i < controls.size(); i++) {
            ControlSpec spec = controls.get(i);
            String groupKey = groupKey(spec);
            if (!groupKey.equals(openGroup)) {
                rows.beginGroup(title(spec));
                openGroup = groupKey;
            }
            boolean lastInGroup = i + 1 == controls.size()
                    || !groupKey(controls.get(i + 1)).equals(groupKey);
            boolean hint = spec.kind != ControlSpec.Kind.COMPOSITE && spec.hintKey != null;
            boolean hintAbove = hint && spec.hintPlacement == ControlSpec.HintPlacement.ABOVE;
            // A trailing hint is the card's last line, so the control above it keeps its gap; a
            // leading hint leaves the control as the last line of the card.
            boolean controlLast = !hint || hintAbove;
            int gap = controlLast && lastInGroup ? 0 : INNER_ROW_GAP_DP;
            if (spec.kind == ControlSpec.Kind.COMPOSITE) {
                EditorComposites.render(spec, context, rows, host);
            } else {
                if (spec.labelKey != null) {
                    rows.addOption(rows.text(rows.resource(spec.labelKey), LABEL_TEXT_SP,
                            EditorRows.GROUP_TITLE_COLOR, true), rows.matchWrap(INNER_ROW_GAP_DP));
                }
                if (hintAbove) {
                    rows.addOption(rows.text(rows.resource(spec.hintKey), HINT_TEXT_SP, HINT_COLOR,
                            false), rows.matchWrap(INNER_ROW_GAP_DP));
                }
                renderRow(spec, gap);
                if (hint && !hintAbove) {
                    rows.addOption(rows.text(rows.resource(spec.hintKey), HINT_TEXT_SP, HINT_COLOR,
                            false), rows.matchWrap(lastInGroup ? 0 : INNER_ROW_GAP_DP));
                }
            }
        }
        rows.endGroup();
    }

    private void renderRow(ControlSpec spec, int gap) {
        switch (spec.kind) {
            case TOGGLE: {
                @SuppressWarnings("unchecked")
                Settings.Setting<Boolean> setting = (Settings.Setting<Boolean>) spec.setting;
                rows.addOption(rows.toggleRow(setting, rows.settingLabel(setting), effect(spec)),
                        rows.matchWrap(gap));
                break;
            }
            case CHOICE: {
                @SuppressWarnings("unchecked")
                Settings.Setting<String> setting = (Settings.Setting<String>) spec.setting;
                rows.addOption(rows.chipRow(setting,
                        spec.choices.toArray(new String[0]), effect(spec)), rows.matchWrap(gap));
                break;
            }
            case SLIDER:
                if (spec.setting instanceof Settings.StringSetting) {
                    @SuppressWarnings("unchecked")
                    Settings.Setting<String> named = (Settings.Setting<String>) spec.setting;
                    rows.addOption(rows.codecSliderRow(spec.slider, named, effect(spec)),
                            rows.matchWrap(gap));
                } else {
                    rows.addOption(rows.sliderRow(spec.slider,
                            (Settings.IntegerSetting) spec.setting, effect(spec)), rows.matchWrap(gap));
                }
                break;
            case MODE_SLIDER: {
                @SuppressWarnings("unchecked")
                Settings.Setting<String> setting = (Settings.Setting<String>) spec.setting;
                rows.addOption(rows.customSliderRow(setting,
                        (Settings.IntegerSetting) spec.customValue, spec.modeValue, effect(spec)),
                        rows.matchWrap(gap));
                break;
            }
            default:
                throw new IllegalStateException("No editor row for " + spec.kind + " " + spec.setting.key);
        }
    }

    /** Runs after a row's value lands. */
    private Runnable effect(ControlSpec spec) {
        switch (spec.effect) {
            case NONE:
                return null;
            case ARTWORK:
                return host::refreshArtwork;
            case TRACK_TEXT:
                return host::refreshTrackText;
            case FOCUS:
                return host::refreshFocus;
            case BACKGROUND:
                return () -> host.backgroundStyleChanged(spec.element);
            case CHROME:
                return host::chromeChanged;
            case CHIPS:
                return host::chipsChanged;
            case CARD:
                return host::cardChanged;
            case APPLY_PREFERENCES:
                return host::applyPreferences;
            case REBUILD:
                return () -> host.select(spec.element);
            default:
                throw new IllegalStateException("Effect " + spec.effect + " is not wired yet");
        }
    }

    /**
     * Rows with the same resolved title share one card. A row without a title is its own card
     * under the setting's own label; a title that names another setting ("settings_label_" + key)
     * is that setting's card, so a control and its paired row stay together.
     */
    private static String groupKey(ControlSpec spec) {
        String title = spec.groupTitleKey;
        if (title == null) {
            return spec.setting == null ? "composite:" + spec.compositeId : "setting:" + spec.setting.key;
        }
        if (title.startsWith("settings_label_")) return "setting:" + title.substring("settings_label_".length());
        return title;
    }

    private String title(ControlSpec spec) {
        if (spec.groupTitleKey == null) return rows.settingLabel(spec.setting);
        String resolved = rows.resource(spec.groupTitleKey);
        return resolved.isEmpty() ? rows.settingLabel(spec.setting) : resolved;
    }
}
