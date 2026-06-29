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
        return normalized.contains("lrclib empty")
                || normalized.contains("no lrclib result")
                || normalized.contains("lrclib http 404")
                || normalized.contains("cached no-result");
    }
}
