package com.eza.spicyex.lyrics.session;

/**
 * Decides whether a session needs the network at all.
 *
 * <p>A durable cached canonical base is display authority. Network acquisition is an optional
 * refresh path, taken only when there is no base, when the cached base is unsynced and a better
 * source may exist, or when a refresh was explicitly requested. Processing or build changes never
 * appear here — they rederive from the cached base without touching the network.
 */
public final class LyricsSourcePolicy {
    public enum Decision {
        /** Render the cached base; make no source request. */
        USE_CACHED_BASE,
        /** Render the cached base immediately, then probe for a better source. */
        REFRESH_AFTER_CACHED_BASE,
        /** No usable base; a source request is required before anything can render. */
        FETCH_REQUIRED
    }

    private LyricsSourcePolicy() {
    }

    public static Decision decide(boolean hasCachedBase, boolean cachedBaseIsSynced,
                                  boolean alreadyProbedThisSession, boolean explicitRefreshRequested) {
        if (!hasCachedBase) return Decision.FETCH_REQUIRED;
        if (explicitRefreshRequested) return Decision.REFRESH_AFTER_CACHED_BASE;
        // A synced cached base is already the best shape any provider can return. Revisiting the
        // track must cost zero requests.
        if (cachedBaseIsSynced) return Decision.USE_CACHED_BASE;
        // A static base may have a synced upgrade. Probe at most once per process per track.
        return alreadyProbedThisSession ? Decision.USE_CACHED_BASE : Decision.REFRESH_AFTER_CACHED_BASE;
    }

    /** True for timing types that carry per-line or per-syllable synchronisation. */
    public static boolean isSynced(String timingType) {
        if (timingType == null) return false;
        return "Line".equalsIgnoreCase(timingType)
                || "Syllable".equalsIgnoreCase(timingType)
                || "Word".equalsIgnoreCase(timingType);
    }

    /**
     * Effective source language. A manual override is strict: it is used verbatim and never falls
     * back to the detected language, even when the detected value looks more specific.
     */
    public static String effectiveSourceLanguage(String sourceMode, String manualLanguage,
                                                 String detectedLanguage) {
        if ("manual".equalsIgnoreCase(sourceMode)) return manualLanguage == null ? "" : manualLanguage;
        return detectedLanguage == null ? "" : detectedLanguage;
    }

    /** True when a manual source-language override is active and must not be second-guessed. */
    public static boolean isStrictSourceLanguage(String sourceMode) {
        return "manual".equalsIgnoreCase(sourceMode);
    }
}
