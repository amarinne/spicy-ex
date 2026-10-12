package com.eza.spicyex.lyrics.session;

/**
 * Privacy-safe failure record for a derived-layer run. Holds a classification and an exception
 * type name only — never provider payloads, auth headers, or lyric text.
 */
public final class LayerFailure {
    public enum Reason { NONE, TIMEOUT, RATE_LIMITED, CLIENT_ERROR, SERVER_ERROR, MALFORMED, CANCELLED, UNAVAILABLE }

    public static final LayerFailure NONE = new LayerFailure(Reason.NONE, "", 0);

    public final Reason reason;
    /** Exception simple name or a short non-sensitive tag. */
    public final String detail;
    public final int httpStatus;
    /** Exact AI token, empty when the failure belongs to another processor. */
    public final String aiFailureToken;

    public LayerFailure(Reason reason, String detail, int httpStatus) {
        this(reason, detail, httpStatus, false);
    }

    private LayerFailure(Reason reason, String detail, int httpStatus, boolean fromAi) {
        this.reason = reason == null ? Reason.NONE : reason;
        this.detail = Digests.nz(detail);
        this.httpStatus = httpStatus;
        this.aiFailureToken = fromAi && isFailure() ? this.detail : "";
    }

    public static LayerFailure ai(Reason reason, String token, int httpStatus) {
        return new LayerFailure(reason, token, httpStatus, true);
    }

    public static LayerFailure of(Reason reason) {
        return new LayerFailure(reason, "", 0);
    }

    public static LayerFailure http(int status) {
        Reason reason;
        if (status == 429) reason = Reason.RATE_LIMITED;
        else if (status >= 500) reason = Reason.SERVER_ERROR;
        else if (status >= 400) reason = Reason.CLIENT_ERROR;
        else reason = Reason.UNAVAILABLE;
        return new LayerFailure(reason, "", status);
    }

    public boolean isFailure() {
        return reason != Reason.NONE;
    }
}
