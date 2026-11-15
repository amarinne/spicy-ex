package com.eza.spicyex.lyrics.catalog;

import com.eza.spicyex.lyrics.catalog.CatalogSource.ProviderStatus;

/** A failure chosen at its origin. Display text never determines persisted retry policy. */
public final class ProviderFailure extends IllegalStateException {
    public final ProviderStatus status;
    public final String message;

    private ProviderFailure(ProviderStatus status, String message) {
        super(message == null ? "" : message);
        this.status = status;
        this.message = message == null ? "" : message;
    }

    public static ProviderFailure transientError(String message) {
        return new ProviderFailure(ProviderStatus.TRANSIENT_ERROR, message);
    }

    public static ProviderFailure notFound(String message) {
        return new ProviderFailure(ProviderStatus.NOT_FOUND, message);
    }

    public static ProviderFailure disabled(String message) {
        return new ProviderFailure(ProviderStatus.DISABLED, message);
    }

    /** Only a typed parser outcome carries durable evidence through a catch boundary. */
    public static ProviderFailure from(Throwable error, String message) {
        return new ProviderFailure(error instanceof ProviderFailure
                ? ((ProviderFailure) error).status : ProviderStatus.TRANSIENT_ERROR, message);
    }

    /** Use only with the actual response code, never a code extracted from exception text. */
    public static ProviderFailure httpError(int statusCode, String message) {
        return new ProviderFailure(ProviderFailureClassifier.httpStatus(statusCode), message);
    }
}
