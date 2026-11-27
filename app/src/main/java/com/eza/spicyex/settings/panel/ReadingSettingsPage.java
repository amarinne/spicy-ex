package com.eza.spicyex.settings.panel;

import android.widget.TextView;

import com.eza.spicyex.Settings;
import com.eza.spicyex.SettingsStore;
import com.eza.spicyex.lyrics.language.MandarinReadings;
import com.eza.spicyex.settings.values.ChineseMode;
import com.eza.spicyex.ui.PanelDialog;
import com.eza.spicyex.ui.SettingsUiStrings;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/** One confirming page for each language, using the shared dialog choices and switches. */
final class ReadingSettingsPage {
    private final PanelDialog dialog;
    private final SettingsStore store;
    private final SettingsUiStrings strings;
    private final Consumer<Map<String, ?>> onSave;
    private final Map<String, Object> changes = new LinkedHashMap<>();
    private TextView save;

    ReadingSettingsPage(PanelDialog dialog, SettingsStore store, SettingsUiStrings strings,
                        Consumer<Map<String, ?>> onSave) {
        this.dialog = dialog;
        this.store = store;
        this.strings = strings;
        this.onSave = onSave;
    }

    void chinese(Settings.StringSetting reading) {
        if (store.get(Settings.TRANSLITERATION_ENABLED)) {
            String initial = store.get(reading);
            ChineseReadingSelection selection = new ChineseReadingSelection(initial, MandarinReadings.available());
            Map<ChineseMode, PanelDialog.OptionRow> modes = new EnumMap<>(ChineseMode.class);
            Map<Boolean, PanelDialog.OptionRow> styles = new LinkedHashMap<>();
            Runnable render = () -> {
                ChineseMode mode = selection.mode();
                for (Map.Entry<ChineseMode, PanelDialog.OptionRow> row : modes.entrySet()) {
                    row.getValue().update(chineseLanguage(reading, row.getKey()), mode == row.getKey(), true);
                }
                String standard = mode == ChineseMode.PINYIN || mode == ChineseMode.JYUTPING
                        ? label(reading, mode) : strings.get("settings_reading_standard", "Standard");
                styles.get(false).update(standard, selection.hasStyle() && !selection.usesEza(),
                        selection.hasStyle());
                String eza = selection.hasStyle() && !selection.offersEza()
                        ? "eza式 · " + strings.get("settings_reading_pack_required", "Language pack required")
                        : "eza式";
                styles.get(true).update(eza, selection.usesEza(), selection.offersEza());
                stage(reading, initial, selection.isDirty() ? selection.selected().storedLabel() : initial);
                dialog.refit();
            };
            dialog.sectionHeading(strings.setting(reading));
            for (ChineseMode mode : new ChineseMode[]{ChineseMode.AUTO, ChineseMode.PINYIN,
                    ChineseMode.JYUTPING, ChineseMode.OFF}) {
                addChineseMode(modes, mode, selection, render);
            }
            dialog.sectionHeading(strings.get("settings_reading_style", "Reading style"));
            styles.put(false, dialog.choiceRow("", () -> {
                selection.selectEza(false);
                render.run();
            }));
            styles.put(true, dialog.choiceRow("", () -> {
                selection.selectEza(true);
                render.run();
            }));
            dialog.divider();
            addChineseMode(modes, ChineseMode.CYCLE, selection, render);
            toggle((Settings.BooleanSetting) Settings.CHINESE_TONES);
            dialog.divider();
            render.run();
        }
        Settings.StringSetting form = (Settings.StringSetting) Settings.CHINESE_CHARACTER_FORM;
        dialog.sectionHeading(strings.setting(form));
        choices(form);
        footer();
    }

    void cyrillic(Settings.StringSetting reading) {
        choices(reading);
        dialog.divider();
        toggle((Settings.BooleanSetting) Settings.CYRILLIC_KEEP_SIGNS);
        footer();
    }

    private void addChineseMode(Map<ChineseMode, PanelDialog.OptionRow> rows, ChineseMode mode,
                                ChineseReadingSelection selection, Runnable render) {
        rows.put(mode, dialog.choiceRow("", () -> {
            selection.selectMode(mode);
            render.run();
        }));
    }

    private String chineseLanguage(Settings.StringSetting reading, ChineseMode mode) {
        switch (mode) {
            case PINYIN: return strings.get("settings_reading_mandarin", "Mandarin");
            case JYUTPING: return strings.get("settings_reading_cantonese", "Cantonese");
            case CYCLE:
                return label(reading, mode) + "\n" + String.format(Locale.ROOT,
                        strings.get("settings_chinese_cycle_sequence", "%1$s → %2$s → %3$s"),
                        label(reading, ChineseMode.PINYIN), label(reading, ChineseMode.JYUTPING),
                        strings.get("settings_reading_hidden", "Hidden"));
            default: return label(reading, mode);
        }
    }

    private String label(Settings.StringSetting setting, ChineseMode mode) {
        return strings.option(setting, mode.storedLabel());
    }

    private void choices(Settings.StringSetting setting) {
        String initial = store.get(setting);
        ArrayList<PanelDialog.Option> options = new ArrayList<>();
        for (String value : setting.allowedValues) {
            PanelDialog.Option option = PanelDialog.Option.of(value, strings.option(setting, value));
            option.separatorBefore = "cycle".equals(value);
            options.add(option);
        }
        dialog.choiceGroup(options, initial, value -> stage(setting, initial, value));
    }

    private void toggle(Settings.BooleanSetting setting) {
        boolean initial = store.get(setting);
        dialog.switchChoice(strings.setting(setting), initial, setting.key, null,
                value -> stage(setting, initial, value));
    }

    private <T> void stage(Settings.Setting<T> setting, T initial, T value) {
        if (initial.equals(value)) changes.remove(setting.key);
        else changes.put(setting.key, value);
        if (save != null) PanelDialog.applySaveArmed(save, !changes.isEmpty());
    }

    private void footer() {
        save = dialog.primaryButton(strings.get("settings_ai_save", "Save"), null);
        save.setOnClickListener(view -> {
            Map<String, ?> confirmed = new LinkedHashMap<>(changes);
            dialog.dismissThen(() -> onSave.accept(confirmed));
        });
        PanelDialog.applySaveArmed(save, false);
        dialog.secondary(strings.get("settings_ai_cancel", "Cancel"), null);
    }
}
