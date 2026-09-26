package com.eza.spicyex.lyrics.session;

/**
 * Small source-shape and language rules shared by rendering and processing. Whether a track visit
 * asks any provider is decided by {@link com.eza.spicyex.lyrics.catalog.AcquisitionPlanner}.
 */
public final class LyricsSourcePolicy {
    private LyricsSourcePolicy() {
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
