package com.eza.spicyex.settings.editor;

import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.eza.spicyex.Settings;
import com.eza.spicyex.settings.values.BackgroundEffect;
import com.eza.spicyex.settings.values.BackgroundEffectPool;
import com.eza.spicyex.settings.values.StoredValues;
import com.eza.spicyex.lyrics.LyricsFontValidator;
import com.eza.spicyex.settings.BlurStrength;
import com.eza.spicyex.settings.registry.ControlSpec;
import com.eza.spicyex.settings.registry.UiContext;
import com.eza.spicyex.ui.PanelDialog;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Hand renderers for the few {@link ControlSpec.Kind#COMPOSITE} entries, registered by
 * {@link ControlSpec#compositeId}. A composite exists only when one control writes more than one
 * setting, or presents a different widget in one layout. Everything else is a generic row.
 */
final class EditorComposites {
    private static final int HINT_GAP_DP = 6;
    private static final int HINT_TEXT_SP = 12;
    private static final int HINT_COLOR = 0x80FFFFFF;
    private static final int SOFT_HINT_COLOR = 0x7AFFFFFF;
    /** The top percent of the one blur strength; the standard distance curve owns it. */
    private static final int BLUR_PERCENT_MAX = BlurStrength.LEGACY_BAND;

    private EditorComposites() { }

    static void render(ControlSpec spec, UiContext context, EditorRows rows,
            EditorControlRenderer.Host host) {
        if ("background_effect_picker".equals(spec.compositeId)) {
            backgroundEffectPicker(spec, context, rows, host);
            return;
        }
        if ("background_random_effects".equals(spec.compositeId)) {
            backgroundRandomEffects(spec, context, rows, host);
            return;
        }
        if ("track_info_position".equals(spec.compositeId)) {
            trackInfoPosition(spec, context, rows, host);
            return;
        }
        if ("font_chips".equals(spec.compositeId)) {
            fontChips(spec, rows, host);
            return;
        }
        if ("custom_font_chooser".equals(spec.compositeId)) {
            customFontChooser(spec, rows, host);
            return;
        }
        if ("distant_line_blur".equals(spec.compositeId)) {
            distantLineBlur(spec, rows);
            return;
        }
        // The grip/drag-only composites (artwork_resize_grip, focus_drag_handle) and the tab
        // strips (lyrics_tabs, card_tabs) are not option rows and never reach this point.
        throw new IllegalStateException("No editor renderer for composite " + spec.compositeId);
    }

    private static void backgroundEffectPicker(ControlSpec spec, UiContext context, EditorRows rows,
            EditorControlRenderer.Host host) {
        BackgroundEffect current = StoredValues.fromStored(BackgroundEffect.class,
                rows.safeGetString(Settings.BACKGROUND_EFFECT));
        LinearLayout row = rows.horizontalRow();
        row.setTag("setting." + spec.setting.key);
        TextView value = rows.text(effectLabel(rows, current), 15, EditorRows.TEXT_COLOR, false);
        value.setSingleLine(true);
        value.setEllipsize(android.text.TextUtils.TruncateAt.END);
        value.setContentDescription(rows.format("background_effect_current",
                "Current effect: %1$s", effectLabel(rows, current)));
        row.addView(value, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView change;
        if (context.ambientEffectsAvailable) {
            change = rows.chip(rows.s("background_effect_change", "Change"));
            change.setContentDescription(rows.s("background_effect_change_description",
                    "Choose background effect"));
            change.setOnClickListener(v -> showFixedPicker(spec, rows, host, current));
        } else {
            change = rows.text(rows.s("background_effect_requires_api", "Requires Android 13+"),
                    14, HINT_COLOR, false);
            change.setContentDescription(change.getText());
        }
        LinearLayout.LayoutParams changeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        changeLp.leftMargin = rows.dp(8);
        row.addView(change, changeLp);
        rows.addOption(row, rows.matchWrap(HINT_GAP_DP));
        addHint(spec, rows);
    }

    private static void backgroundRandomEffects(ControlSpec spec, UiContext context, EditorRows rows,
            EditorControlRenderer.Host host) {
        BackgroundEffectPool pool = BackgroundEffectPool.parse(
                rows.safeGetString(Settings.BACKGROUND_RANDOM_EFFECTS));
        LinearLayout row = rows.horizontalRow();
        row.setTag("setting." + spec.setting.key);
        String summary = rows.format("background_random_count", "%1$d of %2$d selected",
                pool.size(), BackgroundEffect.fixedValues().size());
        TextView value = rows.text(summary, 15, EditorRows.TEXT_COLOR, false);
        value.setSingleLine(true);
        value.setContentDescription(summary);
        row.addView(value, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView choose;
        if (context.ambientEffectsAvailable) {
            choose = rows.chip(rows.s("background_random_choose", "Choose"));
            choose.setContentDescription(rows.s("background_random_choose_description",
                    "Choose effects for Random"));
            choose.setTag(EditorRows.RANDOM_POOL_OPEN_TAG);
            choose.setOnClickListener(v -> showRandomPool(spec, rows, host, pool));
        } else {
            choose = rows.text(rows.s("background_effect_requires_api", "Requires Android 13+"),
                    14, HINT_COLOR, false);
            choose.setContentDescription(choose.getText());
        }
        LinearLayout.LayoutParams chooseLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        chooseLp.leftMargin = rows.dp(8);
        row.addView(choose, chooseLp);
        rows.addOption(row, rows.matchWrap(HINT_GAP_DP));
        addHint(spec, rows);
    }

    private static void addHint(ControlSpec spec, EditorRows rows) {
        String hint = rows.resource(spec.hintKey);
        if (!hint.isEmpty()) {
            rows.addOption(rows.text(hint, HINT_TEXT_SP, HINT_COLOR, false), rows.matchWrap(0));
        }
    }

    private static String effectLabel(EditorRows rows, BackgroundEffect effect) {
        return rows.optionLabel((Settings.StringSetting) Settings.BACKGROUND_EFFECT,
                effect.storedLabel());
    }

    private static void showFixedPicker(ControlSpec spec, EditorRows rows,
            EditorControlRenderer.Host host, BackgroundEffect current) {
        PanelDialog dialog = new PanelDialog(rows.activity(),
                rows.s("background_effect_title", "Background effect"));
        EditText search = dialog.field(false, "");
        search.setInputType(InputType.TYPE_CLASS_TEXT);
        search.setHint(rows.s("background_effect_search", "Search effects"));
        search.setContentDescription(rows.s("background_effect_search", "Search effects"));
        List<View> optionViews = new ArrayList<>();
        Runnable render = () -> {
            removeViews(optionViews);
            String query = search.getText() == null ? "" : search.getText().toString();
            int shown = 0;
            for (BackgroundEffect effect : BackgroundEffect.pickerValues()) {
                String label = effectLabel(rows, effect);
                if (!EffectPickerSearch.matches(query, label, effect.stableId())) continue;
                TextView option = dialog.option(label, effect == current, () ->
                        rows.apply(Settings.BACKGROUND_EFFECT, effect.storedLabel(),
                                () -> host.backgroundStyleChanged(spec.element)));
                option.setTag(EditorRows.choiceTag(Settings.BACKGROUND_EFFECT.key,
                        effect.storedLabel()));
                option.setContentDescription(label);
                optionViews.add(option);
                shown++;
            }
            if (shown == 0) {
                TextView empty = rows.text(rows.s("background_search_empty",
                        "No matching effects"), HINT_TEXT_SP, HINT_COLOR, false);
                dialog.add(empty);
                optionViews.add(empty);
            }
            dialog.refit();
        };
        search.addTextChangedListener(searchWatcher(render));
        render.run();
        dialog.secondary(rows.s("cancel", "Cancel"), null);
        dialog.show();
    }

    private static void showRandomPool(ControlSpec spec, EditorRows rows,
            EditorControlRenderer.Host host, BackgroundEffectPool pool) {
        PanelDialog dialog = new PanelDialog(rows.activity(),
                rows.s("background_random_title", "Effects in Random"));
        Set<BackgroundEffect> working = new LinkedHashSet<>(pool.effects());
        TextView count = rows.text("", HINT_TEXT_SP, HINT_COLOR, false);
        dialog.add(count);
        EditText search = dialog.field(false, "");
        search.setInputType(InputType.TYPE_CLASS_TEXT);
        search.setHint(rows.s("background_random_search", "Search effects"));
        search.setContentDescription(rows.s("background_random_search", "Search effects"));
        List<View> optionViews = new ArrayList<>();
        Map<String, PanelDialog.SwitchChoice> choices = new LinkedHashMap<>();
        Runnable paintCount = () -> count.setText(rows.format("background_random_count",
                "%1$d of %2$d selected", working.size(), BackgroundEffect.fixedValues().size()));
        Runnable render = () -> {
            removeViews(optionViews);
            choices.clear();
            String query = search.getText() == null ? "" : search.getText().toString();
            int shown = 0;
            for (BackgroundEffect effect : BackgroundEffect.fixedValues()) {
                String label = effectLabel(rows, effect);
                if (!EffectPickerSearch.matches(query, label, effect.stableId())) continue;
                PanelDialog.SwitchChoice choice = dialog.switchChoice(label,
                        working.contains(effect),
                        EditorRows.choiceTag(Settings.BACKGROUND_RANDOM_EFFECTS.key,
                                effect.storedLabel()),
                        next -> next || working.size() > 1,
                        next -> {
                            if (next) working.add(effect);
                            else working.remove(effect);
                            paintCount.run();
                        });
                choices.put(effect.stableId(), choice);
                optionViews.add(choice.view());
                shown++;
            }
            if (shown == 0) {
                TextView empty = rows.text(rows.s("background_search_empty",
                        "No matching effects"), HINT_TEXT_SP, HINT_COLOR, false);
                dialog.add(empty);
                optionViews.add(empty);
            }
            paintCount.run();
            dialog.refit();
        };
        search.addTextChangedListener(searchWatcher(render));
        render.run();
        TextView done = dialog.primaryButton(rows.s("done", "Done"), () -> {
            BackgroundEffectPool next = BackgroundEffectPool.of(working);
            rows.apply(Settings.BACKGROUND_RANDOM_EFFECTS, next.encode(),
                    () -> host.backgroundStyleChanged(spec.element));
        });
        TextView cancel = dialog.secondaryButton(rows.s("cancel", "Cancel"), null);
        EditorRows.RandomPoolAgent agent = (action, argument) -> {
            switch (action) {
                case "search":
                    search.setText(argument == null ? "" : argument);
                    return null;
                case "toggle": {
                    BackgroundEffect effect = BackgroundEffect.fixedFromText(argument);
                    if (effect == null) return "unknown fixed effect '" + argument + "'";
                    PanelDialog.SwitchChoice choice = choices.get(effect.stableId());
                    if (choice == null || !choice.view().isShown()) {
                        return "the effect '" + effect.stableId() + "' is not on screen";
                    }
                    // The row's own click owner: the same guard, set, and repaint a tap produces.
                    choice.view().performClick();
                    return null;
                }
                case "apply":
                    done.performClick();
                    return null;
                case "cancel":
                    cancel.performClick();
                    return null;
                case "status": {
                    String query = search.getText() == null ? "" : search.getText().toString();
                    String persisted = BackgroundEffectPool.parse(rows.safeGetString(
                            Settings.BACKGROUND_RANDOM_EFFECTS)).encode();
                    return EditorRows.randomPoolStatus(dialog.isShowing(), query,
                            BackgroundEffectPool.of(working).encode(), persisted, choices.size());
                }
                default:
                    return "expected search, toggle, apply, cancel, or status";
            }
        };
        rows.randomPoolAgent(agent);
        dialog.onDismiss(() -> rows.clearRandomPoolAgent(agent));
        dialog.show();
    }

    private static TextWatcher searchWatcher(Runnable render) {
        return new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(android.text.Editable s) { render.run(); }
        };
    }

    private static void removeViews(List<View> views) {
        for (View view : views) {
            ViewParent parent = view.getParent();
            if (parent instanceof ViewGroup) ((ViewGroup) parent).removeView(view);
        }
        views.clear();
    }

    /**
     * Distant-line blur is one continuous control: 0% is off and the percent strengthens the
     * blur. The stored value also carries the distance curve, so the control shows the percent
     * and writes the standard curve.
     */
    private static void distantLineBlur(ControlSpec spec, EditorRows rows) {
        rows.addOption(rows.text(rows.resource(spec.hintKey), HINT_TEXT_SP, SOFT_HINT_COLOR, false),
                rows.matchWrap(HINT_GAP_DP));
        int blur = BlurStrength.percent(rows.safeGet(Settings.LYRICS_BLUR_STRENGTH));
        rows.addOption(rows.dragRow(0, BLUR_PERCENT_MAX, blur, "%", 0,
                value -> rows.put(Settings.LYRICS_BLUR_STRENGTH, value)), rows.matchWrap(0));
    }

    /**
     * Two-column's left column is the readout's own column placement, so the only position choice
     * that means anything there is whether it shows at all: the four floating placements have
     * nowhere to go. One on/off row, same chip style as every other row: On writes "Top" (any
     * non-Off value shows the column, so the first stored one is as good as any), Off clears it.
     */
    private static void trackInfoPosition(ControlSpec spec, UiContext context, EditorRows rows,
            EditorControlRenderer.Host host) {
        if (context.twoColumn) {
            rows.addOption(rows.toggleRow(
                    rows.resource("settings_layout_editor_show_artwork_info"),
                    !"Off".equals(rows.safeGetString(Settings.TRACK_INFO_POSITION)),
                    (toggle, on) -> rows.apply(Settings.TRACK_INFO_POSITION, on ? "Top" : "Off", () -> {
                        host.refreshArtwork();
                        host.refreshTrackText();
                    })), rows.matchWrap(0));
            return;
        }
        rows.addOption(rows.chipRow(Settings.TRACK_INFO_POSITION,
                spec.choices.toArray(new String[0]), () -> {
                    host.refreshArtwork();
                    host.refreshTrackText();
                    host.select(ControlSpec.Element.ARTWORK);
                }), rows.matchWrap(HINT_GAP_DP));
        rows.addOption(rows.text(rows.resource(spec.hintKey), HINT_TEXT_SP, HINT_COLOR, false),
                rows.matchWrap(0));
    }

    /**
     * Spotify and Apple apply at once. Custom only opens the chooser: the stored mode changes once
     * a font is actually picked, so backing out leaves the previous font in place.
     */
    private static void fontChips(ControlSpec spec, EditorRows rows,
            EditorControlRenderer.Host host) {
        String[] values = spec.choices.toArray(new String[0]);
        LinearLayout row = rows.horizontalRow();
        String current = rows.safeGetString(Settings.LYRICS_FONT);
        TextView[] chips = new TextView[values.length];
        for (int i = 0; i < values.length; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.leftMargin = rows.dp(3);
            lp.rightMargin = rows.dp(3);
            TextView chip = rows.chip(rows.optionLabel((Settings.StringSetting) Settings.LYRICS_FONT,
                    values[i]));
            chip.setTag(EditorRows.choiceTag(Settings.LYRICS_FONT.key, values[i]));
            chips[i] = chip;
            row.addView(chip, lp);
            rows.paintChip(chip, values[i].equals(current));
        }
        for (int i = 0; i < values.length; i++) {
            String value = values[i];
            TextView chip = chips[i];
            chip.setOnClickListener(v -> {
                if ("custom".equals(value)) {
                    host.chooseFont(chip);
                    return;
                }
                for (int j = 0; j < values.length; j++) rows.paintChip(chips[j], values[j].equals(value));
                rows.apply(Settings.LYRICS_FONT, value,
                        () -> host.select(ControlSpec.Element.LYRICS));
            });
        }
        boolean customRowFollows = "custom".equals(rows.safeGetString(Settings.LYRICS_FONT));
        rows.addOption(row, rows.matchWrap(customRowFollows ? HINT_GAP_DP : 0));
    }

    /** Font hint, the current-selection dropdown, then the selected font's coverage summary. */
    private static void customFontChooser(ControlSpec spec, EditorRows rows,
            EditorControlRenderer.Host host) {
        rows.addOption(rows.text(rows.resource(spec.hintKey),
                HINT_TEXT_SP, SOFT_HINT_COLOR, false), rows.matchWrap(HINT_GAP_DP));

        String path = rows.safeGetString(Settings.LYRICS_FONT_CUSTOM_PATH);
        TextView button = rows.chip(fontDisplayName(rows, path));
        button.setPadding(rows.dp(10), rows.dp(10), rows.dp(10), rows.dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(rows.dp(10));
        bg.setColor(0x22FFFFFF);
        bg.setStroke(rows.dp(1), 0x33FFFFFF);
        button.setBackground(bg);
        button.setTextColor(EditorRows.TEXT_COLOR);
        button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        button.setOnClickListener(v -> host.chooseFont(button));
        rows.addOption(button, rows.matchWrap(HINT_GAP_DP));

        String summary = fontCoverageSummary(rows, path);
        if (!summary.isEmpty()) {
            rows.addOption(rows.text(summary, HINT_TEXT_SP, HINT_COLOR, false), rows.matchWrap(0));
        }
    }

    /** System family name, the filename portion of a file path, or the empty-selection label. */
    private static String fontDisplayName(EditorRows rows, String path) {
        if (path == null || path.isEmpty()) {
            return rows.s("font_no_selection", "Select font...");
        }
        if (!path.contains("/") && !path.contains("\\")) {
            return path;
        }
        int lastSep = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        String name = lastSep >= 0 ? path.substring(lastSep + 1) : path;
        if (name.length() > 28) name = name.substring(0, 25) + "...";
        return name;
    }

    /**
     * Mirrors PanelDialogs#reportFontCoverage's resolution order (a real file on disk, else an
     * installed/system font family name) so the same value gets the same verdict in either surface.
     */
    private static String fontCoverageSummary(EditorRows rows, String path) {
        if (path == null || path.isEmpty()) return "";
        Typeface typeface = null;
        File file = new File(path);
        if (file.isFile()) {
            try {
                typeface = Typeface.createFromFile(file);
            } catch (Throwable ignored) {
            }
        }
        if (typeface == null) typeface = Typeface.create(path, Typeface.NORMAL);
        List<String> missing = LyricsFontValidator.missingScripts(typeface);
        if (missing.isEmpty()) {
            String covered = rows.resource("settings_lyrics_font_check_all_covered");
            return covered.isEmpty() ? "Covers every supported language" : covered;
        }
        String prefix = rows.resource("settings_lyrics_font_check_missing");
        if (prefix.isEmpty()) prefix = "Falls back for";
        return prefix + ": " + String.join(", ", missing);
    }
}
