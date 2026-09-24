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
        // A fallback miss cannot establish absence while the remote source is temporarily unavailable.
        // Legacy "spicy ..." prefixes are kept so errors produced before the rename still classify.
        if (normalized.contains("spicy rate-limited") || normalized.contains("spicy upstream-error")
                || normalized.contains("spicy queued") || normalized.contains("spicy auth rejected")
                || normalized.contains("spicy operation 0 missing")
                || normalized.contains("spicy request cancelled")
                || normalized.contains("apple music rate-limited") || normalized.contains("apple music upstream-error")
                || normalized.contains("apple music queued") || normalized.contains("apple music auth rejected")
                || normalized.contains("apple music operation 0 missing")
                || normalized.contains("apple music request cancelled")
                || normalized.contains("remote source disabled")) return false;
        return normalized.contains("lrclib empty")
                || normalized.contains("no lrclib result")
                || normalized.contains("lrclib http 404")
                || normalized.contains("cached no-result");
    }
}
