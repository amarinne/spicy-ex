package com.eza.spicyex.lyrics.catalog;

import com.eza.spicyex.lyrics.catalog.CatalogSource.ProviderStatus;
import com.eza.spicyex.lyrics.catalog.CatalogSource.SourceId;

import java.util.Locale;

/**
 * Maps terminal provider outcomes to persisted {@link ProviderStatus}. Exact-ID and search
 * misses are both durable not-found results (with different retry horizons applied by the
 * refresh policy); transport and server failures are transient; parser and protocol failures
 * are transient too — the stored adapter revision on the next success is what retires them.
 */
public final class ProviderFailureClassifier {
    private ProviderFailureClassifier() {
    }

    public static ProviderStatus classify(SourceId source, String error) {
        if (error == null || error.trim().isEmpty()) return ProviderStatus.TRANSIENT_ERROR;
        String v = error.toLowerCase(Locale.ROOT);
        if (v.contains("disabled")) return ProviderStatus.DISABLED;
        // Durable absence phrases shared with LyricsFetchErrors, plus the AMLL and general
        // shapes that mean the same thing: the provider answered and has nothing.
        if (v.contains("lrclib empty") || v.contains("no lrclib result")
                || v.contains("lrclib http 404") || v.contains("cached no-result")
                || v.contains("404") || v.contains("no match") || v.contains("no result")
                || v.contains("empty") || v.contains("no lyrics")) {
            return ProviderStatus.NOT_FOUND;
        }
        return ProviderStatus.TRANSIENT_ERROR;
    }
}
