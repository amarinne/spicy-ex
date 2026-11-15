package com.eza.spicyex.lyrics.catalog;

import com.eza.spicyex.lyrics.catalog.CatalogSource.ProviderStatus;
import com.eza.spicyex.lyrics.catalog.CatalogSource.SourceId;

/** Transport status classification. Parser and network errors cannot establish absence. */
public final class ProviderFailureClassifier {
    private ProviderFailureClassifier() {
    }

    public static ProviderStatus httpStatus(int statusCode) {
        return statusCode == 404 ? ProviderStatus.NOT_FOUND : ProviderStatus.TRANSIENT_ERROR;
    }

    /** Legacy untyped messages have no evidence of absence or disabled configuration. */
    public static ProviderStatus classify(SourceId source, String error) {
        return ProviderStatus.TRANSIENT_ERROR;
    }
}
