package com.eza.spicyex.auto;

import android.os.Bundle;
import com.eza.spicyex.Settings;
import com.eza.spicyex.SettingsStore;

/** Spotify owns persistence; the provider transports the secondary-text selection. */
public final class AutoDisplaySettings {
    private AutoDisplaySettings() { }

    public static void write(Bundle state, SettingsStore store) {
        state.putString(Settings.AUTO_SECONDARY_TEXT.key, store.get(Settings.AUTO_SECONDARY_TEXT));
    }

    public static void sanitize(Bundle source, Bundle destination) {
        destination.putString(Settings.AUTO_SECONDARY_TEXT.key, secondary(source));
    }

    public static String secondary(Bundle state) {
        return Settings.AUTO_SECONDARY_TEXT.coerce(state == null ? null
                : state.getString(Settings.AUTO_SECONDARY_TEXT.key));
    }

    public static boolean showReading(Bundle state) { return AutoSecondaryText.showReading(secondary(state)); }
    public static boolean showTranslation(Bundle state) { return AutoSecondaryText.showTranslation(secondary(state)); }
    public static int pauseSeconds(Bundle state) { return 3; }
    public static int signature(Bundle state) { return secondary(state).hashCode(); }
}
