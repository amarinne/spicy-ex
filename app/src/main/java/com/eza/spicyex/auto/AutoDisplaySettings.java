package com.eza.spicyex.auto;

import android.os.Bundle;
import com.eza.spicyex.Settings;
import com.eza.spicyex.SettingsStore;
import com.eza.spicyex.settings.values.SecondaryTextMode;
import com.eza.spicyex.settings.values.StoredValues;

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

    public static boolean showReading(Bundle state) { return mode(state).showsTransliteration(); }
    public static boolean showTranslation(Bundle state) { return mode(state).showsTranslation(); }
    public static int pauseSeconds(Bundle state) { return 3; }
    public static int signature(Bundle state) { return secondary(state).hashCode(); }

    /** The transported label as the value it names. */
    private static SecondaryTextMode mode(Bundle state) {
        return StoredValues.fromStored(SecondaryTextMode.class, secondary(state));
    }
}
