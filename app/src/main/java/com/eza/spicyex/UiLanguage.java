package com.eza.spicyex;

import android.content.Context;

/**
 * The single owner of the Spicy EX UI language for one process.
 *
 * <p>Before this, every surface built and kept its own {@link SettingsUiStrings}: the settings
 * panel recreated its copy only when the language changed inside that panel, the fullscreen
 * shell held a {@code final} field built once at construction, and the report dialog built a
 * fresh copy per call. A language change therefore reached exactly one surface. The shell kept
 * rendering its construction-time language until it happened to remount, at which point the
 * text changed on its own — the reported "I did not change the language but the UI changed
 * language" defect.
 *
 * <p>Ownership now lives here. Callers pass the persisted language they already read; the
 * provider rebuilds only when that value changes, so every surface in the process observes the
 * same language immediately, and no surface can cache a stale copy.
 *
 * <p>Process-scoped by design: Xposed and LSPosed hook code runs in Spotify's process, while a
 * module activity would run in the module process. Each process keeps its own resolved instance,
 * which is correct because each reads the same persisted preference.
 */
public final class UiLanguage {
    /** Persisted language the cached instance was built for; null until first use. */
    private static volatile String cachedLanguage;
    private static volatile SettingsUiStrings cached;

    private UiLanguage() {
    }

    /**
     * Strings for the persisted language, rebuilding only when that language changes.
     *
     * @param context        any context that can reach the module package resources
     * @param storedLanguage the persisted {@code Settings.UI_LANGUAGE} value
     */
    public static SettingsUiStrings strings(Context context, String storedLanguage) {
        String language = storedLanguage == null || storedLanguage.isEmpty() ? "en" : storedLanguage;
        SettingsUiStrings current = cached;
        if (current != null && language.equals(cachedLanguage)) return current;
        synchronized (UiLanguage.class) {
            if (cached == null || !language.equals(cachedLanguage)) {
                cached = new SettingsUiStrings(context, language);
                cachedLanguage = language;
            }
            return cached;
        }
    }

    /** Drops the reservation so the next call rebuilds. Only needed to force a re-read. */
    public static void invalidate() {
        synchronized (UiLanguage.class) {
            cached = null;
            cachedLanguage = null;
        }
    }
}
