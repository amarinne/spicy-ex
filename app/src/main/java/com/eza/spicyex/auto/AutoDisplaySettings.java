package com.eza.spicyex.auto;

import android.os.Bundle;
import com.eza.spicyex.Settings;
import com.eza.spicyex.SettingsStore;
import com.eza.spicyex.settings.values.SecondaryTextMode;
import com.eza.spicyex.settings.values.StoredValues;

/** Spotify owns persistence; the provider transports the Auto display choices. */
public final class AutoDisplaySettings {
    private AutoDisplaySettings() { }

    public static void write(Bundle state, SettingsStore store) {
        state.putString(Settings.AUTO_SECONDARY_TEXT.key, store.get(Settings.AUTO_SECONDARY_TEXT));
        state.putBoolean(Settings.AUTO_DUET.key, store.get(Settings.AUTO_DUET));
    }

    public static void sanitize(Bundle source, Bundle destination) {
        sanitize(source, null, destination);
    }

    /**
     * A packet that omits a selection keeps the previous one. Row updates are frequent and not
     * every producer repeats the settings, so defaulting them would flip the extra line, the
     * song-details row, and the duet scene on every lyric change.
     */
    public static void sanitize(Bundle source, Bundle previous, Bundle destination) {
        boolean carriedText = source != null && source.containsKey(Settings.AUTO_SECONDARY_TEXT.key);
        destination.putString(Settings.AUTO_SECONDARY_TEXT.key,
                carriedText ? secondary(source) : secondary(previous));
        boolean carriedDuet = source != null && source.containsKey(Settings.AUTO_DUET.key);
        destination.putBoolean(Settings.AUTO_DUET.key, carriedDuet ? duet(source) : duet(previous));
    }

    public static String secondary(Bundle state) {
        return Settings.AUTO_SECONDARY_TEXT.coerce(state == null ? null
                : state.getString(Settings.AUTO_SECONDARY_TEXT.key));
    }

    /** The duet toggle; missing settings keep the schema default. */
    public static boolean duet(Bundle state) {
        return Settings.AUTO_DUET.coerce(state != null && state.containsKey(Settings.AUTO_DUET.key)
                ? state.getBoolean(Settings.AUTO_DUET.key) : null);
    }

    public static boolean showReading(Bundle state) { return mode(state).showsTransliteration(); }
    public static boolean showTranslation(Bundle state) { return mode(state).showsTranslation(); }
    /** Any mode other than Main only renders an extra row under the lyric. */
    public static boolean extraLine(Bundle state) { return mode(state) != SecondaryTextMode.MAIN_ONLY; }
    /** The release delay the policy holds a paused row queue for, in whole seconds. */
    public static int pauseSeconds() { return (int) (AutoPrototypePolicy.PAUSE_RELEASE_MS / 1000L); }
    public static int signature(Bundle state) {
        return 31 * secondary(state).hashCode() + (duet(state) ? 1 : 0);
    }

    /** The transported label as the value it names. */
    private static SecondaryTextMode mode(Bundle state) {
        return StoredValues.fromStored(SecondaryTextMode.class, secondary(state));
    }
}
