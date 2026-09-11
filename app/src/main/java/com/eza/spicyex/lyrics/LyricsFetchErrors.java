package com.eza.spicyex.lyrics;

import java.util.Locale;

import static com.eza.spicyex.lyrics.LyricUtils.isBlank;

/** Classifies fetch failures so durable no-lyrics results do not get mixed with transient errors. */
public final class LyricsFetchErrors {
    private LyricsFetchErrors() {
    }

    public static boolean isDurableNoLyrics(String error) {
        if (isBlank(error)) return false;
        String normalized = error.toLowerCase(Locale.ROOT);
        // A fallback miss cannot establish absence while Spicy is temporarily unavailable.
        if (normalized.contains("spicy rate-limited") || normalized.contains("spicy upstream-error")
                || normalized.contains("spicy queued") || normalized.contains("spicy auth rejected")
                || normalized.contains("spicy operation 0 missing")
                || normalized.contains("spicy request cancelled")) return false;
        return normalized.contains("lrclib empty")
                || normalized.contains("no lrclib result")
                || normalized.contains("lrclib http 404")
                || normalized.contains("cached no-result");
    }
}
