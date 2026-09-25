package com.eza.spicyex.hooks;

import com.eza.spicyex.Settings;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * What the in-app Layout Editor edits, for code outside this package (the settings search, which
 * sends those results to the editor rather than to a panel row that does not exist).
 */
public final class LayoutEditorSettings {
    private LayoutEditorSettings() {
    }

    public static List<Settings.Setting<?>> covered() {
        return Collections.unmodifiableList(Arrays.asList(LyricsLayoutEditController.coveredSettings()));
    }

    /** The next editor opened goes straight to this setting's element, tab and sheet. */
    public static void openOn(Settings.Setting<?> setting) {
        LyricsLayoutEditController.requestFocus(setting);
    }

    /** Whether a covered setting belongs to the Now Playing card editor (else the lyrics one). */
    public static boolean isCardSetting(Settings.Setting<?> setting) {
        return setting != null && setting.key != null && setting.key.startsWith("lyrics_live_card");
    }
}
