package com.eza.spicyex.lyrics.ai;

/**
 * A typed transport or provider failure.
 *
 * <p>Typed rather than an exception message because the runtime's decisions depend on the kind: one
 * of these is retried once, one waits first, and one must never be retried at all. A string would
 * force that decision to be made by matching text.
 *
 * <p>Carries no response body. A provider error body can echo the request back, which would put
 * lyric text into a log.
 */
public final class AiProviderFailure {
    public enum Kind {
        /** 401/403. Never retried. */
        AUTH,
        /** 429. Honours {@code Retry-After}, once. */
        RATE_LIMITED,
        /** Billing exhausted. Never retried. */
        QUOTA,
        /** Other terminal 4xx. */
        REQUEST_REJECTED,
        /**
         * Network error, deadline, or 5xx <em>after dispatch</em>. The request may already have
         * been served and billed, so this is never retried automatically — only on an explicit,
         * warned, user-authorized retry.
         */
        DELIVERY_UNKNOWN,
        /** Response exceeded the byte ceiling. */
        OVERSIZED,
        /** The selected model is gone. Requires reselection, never silent substitution. */
        MODEL_UNAVAILABLE,
        /** The response did not satisfy the contract. Repairable once. */
        PROTOCOL
    }

    public enum Cause { NONE, NETWORK, TIMEOUT, SERVER }

    public final Kind kind;
    public final Cause cause;
    /** Milliseconds the provider asked us to wait, or null. */
    public final Long retryAfterMs;
    public final int status;
    /** Machine token only — never a provider message. */
    public final String detail;
    public final long bytes;

    private AiProviderFailure(Kind kind, Cause cause, Long retryAfterMs, int status, String detail,
                              long bytes) {
        this.kind = kind;
        this.cause = cause == null ? Cause.NONE : cause;
        this.retryAfterMs = retryAfterMs;
        this.status = status;
        this.detail = AiText.nz(detail);
        this.bytes = bytes;
    }

    /**
     * This failure with the HTTP status and the provider's machine error code attached, for the
     * failure dialog and the stored record. Never a message: only a token from a code field.
     */
    public AiProviderFailure withHttp(int httpStatus, String errorToken) {
        return new AiProviderFailure(kind, cause, retryAfterMs,
                status != 0 ? status : httpStatus,
                detail.isEmpty() ? AiText.nz(errorToken) : detail, bytes);
    }

    /**
     * The provider's machine error code from an error body, or empty.
     *
     * <p>Reads only code-shaped fields ({@code error.details[].reason}, {@code error.code},
     * {@code error.status}, {@code error.type}) and keeps a value only when it is one short
     * identifier, so a message or echoed request text can never pass. The proxy failures on
     * 2026-11-18 were {@code TOS_VIOLATION}, {@code api_key_expired}, and
     * {@code model_not_found}; without them every one read as a bare status.
     */
    public static String errorToken(String body) {
        com.google.gson.JsonObject root;
        try {
            com.google.gson.JsonElement parsed = com.google.gson.JsonParser.parseString(
                    AiText.nz(body));
            if (!parsed.isJsonObject()) return "";
            root = parsed.getAsJsonObject();
        } catch (RuntimeException notJson) {
            return "";
        }
        com.google.gson.JsonObject error = root.has("error") && root.get("error").isJsonObject()
                ? root.getAsJsonObject("error") : root;
        if (error.has("details") && error.get("details").isJsonArray()) {
            for (com.google.gson.JsonElement detail : error.getAsJsonArray("details")) {
                if (!detail.isJsonObject()) continue;
                String reason = tokenOf(detail.getAsJsonObject().get("reason"));
                if (!reason.isEmpty()) return reason;
            }
        }
        for (String key : new String[]{"code", "status", "type"}) {
            String token = tokenOf(error.get(key));
            if (!token.isEmpty()) return token;
        }
        return "";
    }

    private static final java.util.regex.Pattern ERROR_TOKEN =
            java.util.regex.Pattern.compile("[A-Za-z][A-Za-z0-9_.:-]{1,63}");

    private static String tokenOf(com.google.gson.JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            return "";
        }
        String text = value.getAsString().trim();
        return ERROR_TOKEN.matcher(text).matches() ? text : "";
    }

    public static AiProviderFailure auth() {
        return new AiProviderFailure(Kind.AUTH, Cause.NONE, null, 0, "", 0L);
    }

    public static AiProviderFailure rateLimited(Long retryAfterMs) {
        return new AiProviderFailure(Kind.RATE_LIMITED, Cause.NONE, retryAfterMs, 0, "", 0L);
    }

    public static AiProviderFailure quota() {
        return new AiProviderFailure(Kind.QUOTA, Cause.NONE, null, 0, "", 0L);
    }

    public static AiProviderFailure requestRejected(int status) {
        return new AiProviderFailure(Kind.REQUEST_REJECTED, Cause.NONE, null, status, "", 0L);
    }

    public static AiProviderFailure deliveryUnknown(Cause cause, int status) {
        return new AiProviderFailure(Kind.DELIVERY_UNKNOWN, cause, null, status, "", 0L);
    }

    public static AiProviderFailure oversized(long bytes) {
        return new AiProviderFailure(Kind.OVERSIZED, Cause.NONE, null, 0, "", bytes);
    }

    public static AiProviderFailure modelUnavailable() {
        return new AiProviderFailure(Kind.MODEL_UNAVAILABLE, Cause.NONE, null, 0, "", 0L);
    }

    public static AiProviderFailure protocol(String detail) {
        return new AiProviderFailure(Kind.PROTOCOL, Cause.NONE, null, 0, detail, 0L);
    }
}
