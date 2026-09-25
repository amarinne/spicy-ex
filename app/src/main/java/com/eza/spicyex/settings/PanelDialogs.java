package com.eza.spicyex.settings;

import android.graphics.Typeface;
import android.widget.EditText;
import android.widget.TextView;

import com.eza.spicyex.Settings;
import com.eza.spicyex.SettingsStore;
import com.eza.spicyex.SettingsUiStrings;
import com.eza.spicyex.lyrics.CacheStoragePolicy;
import com.eza.spicyex.lyrics.LyricsFontValidator;
import com.eza.spicyex.lyrics.SpicyManualTokenStore;
import com.eza.spicyex.ui.ActionIconDrawable;
import com.eza.spicyex.ui.PanelDialog;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Every dialog the settings panel opens, in one place.
 *
 * <p>Owns the option list (labels, availability reasons, previews, icons), the confirming
 * selector, the cache breakdown, and the Spicy manual-token flow. Writing is delegated: the
 * host performs the store write and decides what to rebuild, so no dialog invents its own
 * persistence path and no write ever happens from a dismiss callback.
 */
public final class PanelDialogs {
    /** What the dialogs need from the panel. */
    public interface Host {
        SettingsStore store();

        SettingsWriter writer();

        SettingsUiStrings strings();

        PanelStyle style();

        /** Writes the value and applies immediate side effects (the UI-language swap). */
        void onOptionChosen(Settings.StringSetting setting, String value);

        /** Post-dismiss structural refresh for a changed setting. */
        void afterSettingChosen(Settings.Setting<?> setting);

        String labelFor(Settings.StringSetting setting, String value);

        /** Row summary text; the cache-size row carries a usage suffix the plain label lacks. */
        String rowSummaryFor(Settings.StringSetting setting, String value);

        PanelSnapshot snapshot();

        PanelStrings panelStrings();

        /** The Spicy manual token changed; refresh the owning section. */
        void onSpicyTokenChanged();
    }

    private final Host host;

    public PanelDialogs(Host host) {
        this.host = host;
    }

    /** Ordinary single-select option dialog; confirming settings route to their own path. */
    public void openSelector(Settings.StringSetting setting, List<String> values, TextView valueView) {
        if (setting == Settings.TRANSLATION_TARGET) {
            openLanguageSelector(setting, values, valueView);
            return;
        }
        if (PanelPolicy.commitPolicyFor(setting) == CommitPolicy.CONFIRMING) {
            openConfirmingSelector(setting, values, valueView);
            return;
        }
        PanelStyle style = host.style();
        SettingsUiStrings strings = host.strings();
        final String current = host.store().get(setting);
        final PanelSnapshot snapshot = host.snapshot();
        // Cache size only: recompute once per dialog so the selected option carries the usage suffix.
        final String usageSuffix = setting == Settings.CACHE_SIZE
                ? " · " + CacheStoragePolicy.formatBytes(
                CacheStoragePolicy.storedTotal(style.context())) + " used"
                : null;
        List<PanelDialog.Option> options = new ArrayList<>();
        for (String val : values) {
            PanelDialog.Option option = PanelDialog.Option.of(val, host.labelFor(setting, val));
            String reason = PanelPolicy.optionUnavailableReason(setting, val, snapshot, host.panelStrings());
            if (!reason.isEmpty()) {
                option.unavailable = true;
                option.suffix = "  · " + reason;
            } else if (usageSuffix != null && val.equals(current)) {
                option.suffix = usageSuffix;
            } else {
                String note = PanelPolicy.optionNote(setting, val, snapshot, host.panelStrings());
                if (!note.isEmpty()) option.detail = note;
            }
            option.preview = SettingLabels.optionPreview(setting.key, val);
            if (setting == Settings.LIKED_SONGS_BUTTON) {
                option.icon = ActionIconDrawable.likedSongsKind(val);
            }
            options.add(option);
        }
        PanelDialog dialog = new PanelDialog(style.context(), strings.setting(setting));
        dialog.listOptions(options, current, value -> {
            // Selecting the already-applied value is a true no-op. In particular,
            // opening and dismissing the language picker must not rebuild the panel.
            if (value.equals(current)) return null;
            host.onOptionChosen(setting, value);
            valueView.setText(host.rowSummaryFor(setting, value));
            // The store write is already committed; the structural rebuild waits for the
            // dismissal animation so a language change does not re-render under the dialog.
            return () -> host.afterSettingChosen(setting);
        });
        dialog.show();
    }

    /**
     * Confirming selector. Tapping a row only moves the pending highlight; nothing is
     * written until Save. Cancel, back, and outside-tap discard.
     */
    public void openConfirmingSelector(Settings.StringSetting setting, List<String> values,
                                       TextView valueView) {
        PanelStyle style = host.style();
        SettingsUiStrings strings = host.strings();
        final String initial = host.store().get(setting);
        List<PanelDialog.Option> options = new ArrayList<>();
        for (String val : values) {
            options.add(PanelDialog.Option.of(val, host.labelFor(setting, val)));
        }
        new PanelDialog(style.context(), strings.setting(setting))
                .confirmingOptions(options, initial, selected -> {
                    if (selected != null && !selected.equals(initial)) {
                        host.onOptionChosen(setting, selected);
                        valueView.setText(host.rowSummaryFor(setting, selected));
                        host.afterSettingChosen(setting);
                    }
                }, strings.get("settings_ai_save", "Save"),
                        strings.get("settings_ai_cancel", "Cancel"))
                .show();
    }

    /**
     * The translation target: 34 languages, so it gets a search field, the current and the
     * phone's languages on top, then everything alphabetically by its name in the UI language,
     * each with the language's own name underneath ("Spanish" / "Español · es").
     */
    public void openLanguageSelector(Settings.StringSetting setting, List<String> values,
                                     TextView valueView) {
        PanelStyle style = host.style();
        SettingsUiStrings strings = host.strings();
        final String initial = host.store().get(setting);
        java.util.Locale uiLocale = java.util.Locale.forLanguageTag(
                strings.selectedLanguage().replace('_', '-').replace("-r", "-"));
        List<PanelDialog.Option> options = new ArrayList<>();
        for (String val : values) {
            PanelDialog.Option option = PanelDialog.Option.of(val, SettingLabels.withoutCode(host.labelFor(setting, val)));
            java.util.Locale own = java.util.Locale.forLanguageTag(val);
            String native_ = SettingLabels.capitalized(own.getDisplayName(own), own);
            option.detail = native_.isEmpty() || native_.equalsIgnoreCase(option.label)
                    ? val : native_ + " \u00b7 " + val;
            // English names too, so "spanish" finds it whatever the UI language is.
            option.keywords = own.getDisplayName(java.util.Locale.ENGLISH);
            options.add(option);
        }
        final java.text.Collator collator = java.text.Collator.getInstance(uiLocale);
        options.sort((a, b) -> collator.compare(a.label, b.label));

        List<String> pinned = new ArrayList<>();
        pinned.add(initial);
        android.os.LocaleList phone = android.os.LocaleList.getDefault();
        for (int i = 0; i < phone.size(); i++) {
            String match = SettingLabels.translationTargetFor(phone.get(i), values);
            if (match != null && !pinned.contains(match)) pinned.add(match);
        }
        String uiMatch = SettingLabels.translationTargetFor(uiLocale, values);
        if (uiMatch != null && !pinned.contains(uiMatch)) pinned.add(uiMatch);
        while (pinned.size() > 4) pinned.remove(pinned.size() - 1);

        new PanelDialog(style.context(), strings.setting(setting))
                .searchableOptions(options, pinned, initial, selected -> {
                    if (selected != null && !selected.equals(initial)) {
                        host.onOptionChosen(setting, selected);
                        valueView.setText(host.rowSummaryFor(setting, selected));
                        host.afterSettingChosen(setting);
                    }
                }, strings.get("settings_ai_save", "Save"),
                        strings.get("settings_ai_cancel", "Cancel"),
                        strings.get("settings_language_search", "Search languages"),
                        strings.get("settings_language_suggested", "Suggested"),
                        strings.get("settings_language_all", "All languages"),
                        strings.get("settings_language_no_match", "No language matches"))
                .show();
    }

    public void promptSpicyToken() {
        PanelStyle style = host.style();
        SettingsUiStrings strings = host.strings();
        PanelDialog dialog = new PanelDialog(style.context(),
                strings.setting(Settings.SPICY_MANUAL_TOKEN)).secure();
        EditText field = dialog.field(true, "");
        dialog.primary(strings.get("settings_ai_save", "Save"), () -> {
            if (SpicyManualTokenStore.save(style.context(), field.getText().toString().trim())) {
                host.onSpicyTokenChanged();
            } else {
                android.widget.Toast.makeText(style.context(),
                        strings.get("settings_spicy_token_rejected", "Token not saved"),
                        android.widget.Toast.LENGTH_SHORT).show();
            }
        });
        dialog.secondary(strings.get("settings_ai_cancel", "Cancel"), null);
        dialog.show();
    }

    /** Custom lyric font: a plain file path rather than a system picker (this module has no
     *  Activity of its own to receive a picker result from inside Spotify's process). Saving
     *  immediately runs {@link LyricsFontValidator} against the chosen file and reports which of
     *  the app's supported scripts it doesn't cover - unsupported scripts still render correctly
     *  via Android's own font fallback either way, this is purely informational up front. */
    public void promptLyricsFontPath() {
        PanelStyle style = host.style();
        SettingsUiStrings strings = host.strings();
        PanelDialog dialog = new PanelDialog(style.context(),
                strings.setting(Settings.LYRICS_FONT_CUSTOM_PATH));
        EditText field = dialog.field(false, host.store().get(Settings.LYRICS_FONT_CUSTOM_PATH));
        dialog.primary(strings.get("settings_ai_save", "Save"), () -> {
            String path = field.getText().toString().trim();
            host.onOptionChosen((Settings.StringSetting) Settings.LYRICS_FONT_CUSTOM_PATH, path);
            host.afterSettingChosen(Settings.LYRICS_FONT_CUSTOM_PATH);
            reportFontCoverage(path);
        });
        dialog.secondary(strings.get("settings_ai_cancel", "Cancel"), null);
        dialog.show();
    }

    private void reportFontCoverage(String path) {
        if (path.isEmpty()) return;
        PanelStyle style = host.style();
        SettingsUiStrings strings = host.strings();
        // Mirrors LyricsTextFactory#resolveLyricTypeface: a real file on disk wins, otherwise
        // treat the text as an installed/system font family name (e.g. "sans-serif-medium")
        // rather than failing outright - Typeface#create() never throws for an unknown name.
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
        PanelDialog result = new PanelDialog(style.context(),
                strings.get("settings_lyrics_font_check_title", "Font language check"));
        if (missing.isEmpty()) {
            result.infoRow(strings.get("settings_lyrics_font_check_result", "Result"),
                    strings.get("settings_lyrics_font_check_all_covered",
                            "Covers every supported language"));
        } else {
            result.infoRow(strings.get("settings_lyrics_font_check_missing", "Falls back for"),
                    String.join(", ", missing));
        }
        result.secondary(strings.get("settings_cache_details_close", "Close"), null);
        result.show();
    }

    public void revealSpicyToken() {
        PanelStyle style = host.style();
        SettingsUiStrings strings = host.strings();
        String token = SpicyManualTokenStore.load(style.context());
        if (token.isEmpty()) return;
        PanelDialog dialog = new PanelDialog(style.context(),
                strings.setting(Settings.SPICY_MANUAL_TOKEN))
                .secure().closeIcon(strings.get("lyrics_ai_close", "Close"));
        dialog.secretValue(token);
        dialog.show();
    }
}
