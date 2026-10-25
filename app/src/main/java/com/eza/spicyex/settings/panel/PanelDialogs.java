package com.eza.spicyex.settings.panel;

import android.widget.EditText;
import android.widget.TextView;

import com.eza.spicyex.Settings;
import com.eza.spicyex.SettingsStore;
import com.eza.spicyex.settings.SettingsWriter;
import com.eza.spicyex.settings.registry.UiContext;
import com.eza.spicyex.ui.SettingsUiStrings;
import com.eza.spicyex.lyrics.providers.LyricsResponseCache;
import com.eza.spicyex.lyrics.cache.CacheStoragePolicy;
import com.eza.spicyex.lyrics.providers.SpicyOrgKeyStore;
import com.eza.spicyex.lyrics.session.AIPaidArtifactCache;
import com.eza.spicyex.lyrics.session.CanonicalSourceCache;
import com.eza.spicyex.ui.ActionIconDrawable;
import com.eza.spicyex.ui.PanelDialog;

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

        /** The applied-state snapshot the panel renders from. */
        UiContext uiContext();

        PanelStrings panelStrings();

        /** The Spicy Lyrics client key changed; refresh the owning section. */
        void onSpicyKeyChanged();
    }

    private final Host host;

    public PanelDialogs(Host host) {
        this.host = host;
    }

    /** Ordinary single-select option dialog; confirming settings route to their own path. */
    public void openSelector(Settings.StringSetting setting, List<String> values, TextView valueView) {
        if (PanelPolicy.commitPolicyFor(setting) == CommitPolicy.CONFIRMING) {
            openConfirmingSelector(setting, values, valueView);
            return;
        }
        PanelStyle style = host.style();
        SettingsUiStrings strings = host.strings();
        final String current = host.store().get(setting);
        final UiContext context = host.uiContext();
        // Cache size only: recompute once per dialog so the selected option carries the usage suffix.
        final String usageSuffix = setting == Settings.CACHE_SIZE
                ? " · " + CacheStoragePolicy.formatBytes(
                CacheStoragePolicy.storedTotal(style.context())) + " used"
                : null;
        List<PanelDialog.Option> options = new ArrayList<>();
        for (String val : values) {
            PanelDialog.Option option = PanelDialog.Option.of(val, host.labelFor(setting, val));
            String reason = PanelPolicy.optionUnavailableReason(setting, val, context, host.panelStrings());
            if (!reason.isEmpty()) {
                option.unavailable = true;
                option.suffix = "  · " + reason;
            } else if (usageSuffix != null && val.equals(current)) {
                option.suffix = usageSuffix;
            }
            option.preview = SettingLabels.optionPreview(setting.key, val);
            if (setting == Settings.LIKED_SONGS_BUTTON) {
                option.icon = ActionIconDrawable.likedSongsKind(val);
            }
            options.add(option);
        }
        PanelDialog dialog = new PanelDialog(style.context(), strings.setting(setting));
        if (setting == Settings.CACHE_SIZE) {
            dialog.headerAction(ActionIconDrawable.Kind.CIRCLE_HELP,
                    strings.get("settings_cache_details_action", "Show cache details"),
                    this::showCacheInfo);
        }
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

    public void showCacheInfo() {
        PanelStyle style = host.style();
        SettingsUiStrings strings = host.strings();
        long aiBytes = AIPaidArtifactCache.usageBytes(style.context());
        new PanelDialog(style.context(), strings.get("settings_cache_details_title", "Cache details"))
                .infoRow(strings.get("settings_cache_details_songs", "Cached songs"),
                        String.valueOf(Math.max(
                                CanonicalSourceCache.entryCount(style.context()),
                                LyricsResponseCache.entryCount(style.context()))))
                .infoRow(strings.get("settings_cache_details_lyrics", "Lyric data cached"),
                        CacheStoragePolicy.formatBytes(
                                CacheStoragePolicy.storedTotal(style.context()) - aiBytes))
                .infoRow(strings.get("settings_cache_details_ai", "AI data cached"),
                        CacheStoragePolicy.formatBytes(aiBytes))
                .secondary(strings.get("settings_cache_details_close", "Close"), null)
                .show();
    }

    public void promptSpicyKey() {
        PanelStyle style = host.style();
        SettingsUiStrings strings = host.strings();
        PanelDialog dialog = new PanelDialog(style.context(),
                strings.setting(Settings.SPICY_ORG_CLIENT_KEY)).secure();
        dialog.paragraph(strings.get("settings_spicy_key_help",
                "Add Spicy EX from the catalog. Paste your personal client key. Save the key. Enable SpicyLyrics.org in Lyrics source."));
        EditText field = dialog.field(true, "");
        dialog.primary(strings.get("settings_ai_save", "Save"), () -> {
            if (SpicyOrgKeyStore.save(style.context(), field.getText().toString().trim())) {
                host.onSpicyKeyChanged();
            } else {
                android.widget.Toast.makeText(style.context(),
                        strings.get("settings_spicy_key_rejected", "The key is not saved. Use a client key that starts with sl_pk_."),
                        android.widget.Toast.LENGTH_SHORT).show();
            }
        });
        dialog.secondary(strings.get("settings_ai_cancel", "Cancel"), null);
        dialog.show();
    }

}
