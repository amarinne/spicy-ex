package com.eza.spicyex.settings;

import com.eza.spicyex.Settings;
import com.eza.spicyex.lyrics.LyricsBackgroundStyle;

/**
 * The single commit point for ordinary setting writes: one write site per value kind.
 *
 * <p>View holders, dialogs, and steppers never touch a store directly; they call here.
 * AI credentials, the Spicy manual token, and source preferences stay behind their explicit
 * adapters and never flow through this writer. Cross-namespace commits (ordinary store plus
 * source preferences) are ordered, not atomic — see {@link SourcePreferencesAdapter}.
 */
public final class SettingsWriter {
    private final TypedStore store;

    public SettingsWriter(TypedStore store) {
        if (store == null) throw new IllegalArgumentException("store == null");
        this.store = store;
    }

    public void put(Settings.BooleanSetting setting, boolean value) {
        store.putBoolean(setting, value);
    }

    public void put(Settings.StringSetting setting, String value) {
        store.putString(setting, value);
    }

    public void put(Settings.IntegerSetting setting, int value) {
        store.putInt(setting, value);
    }

    /** Generic dispatch for settings held as the base type; still lands on one site per kind. */
    public <T> void put(Settings.Setting<T> setting, T value) {
        if (setting instanceof Settings.BooleanSetting) {
            store.putBoolean((Settings.BooleanSetting) setting, (Boolean) value);
        } else if (setting instanceof Settings.StringSetting) {
            store.putString((Settings.StringSetting) setting, (String) value);
        } else if (setting instanceof Settings.IntegerSetting) {
            store.putInt((Settings.IntegerSetting) setting, (Integer) value);
        } else {
            throw new IllegalArgumentException("unsupported setting " + setting.key);
        }
    }

    /**
     * One-time migration from the removed background boolean to the style selector.
     * Runs in the panel constructor; a no-op once the style key exists.
     */
    public void ensureBackgroundStyleMigrated(boolean legacyBackgroundEnabled) {
        if (!store.contains(Settings.BACKGROUND_STYLE)) {
            put(Settings.BACKGROUND_STYLE, legacyBackgroundEnabled
                    ? LyricsBackgroundStyle.ANIMATED_TEXTURE
                    : LyricsBackgroundStyle.GRADIENT);
        }
    }
}
