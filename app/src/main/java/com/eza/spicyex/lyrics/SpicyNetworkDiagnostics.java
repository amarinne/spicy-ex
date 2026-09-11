package com.eza.spicyex.lyrics;

import com.google.gson.*;

/** Last Spicy outcome stays available even when arbitration selects a fallback provider. */
public final class SpicyNetworkDiagnostics {
    public static volatile boolean spicyEnvelopeNoticePresent;
    public static volatile Integer spicyQueryStatus;
    public static volatile String source = "";
    public static volatile String reason = "";
    public static volatile int transportStatus;
    public static volatile Long retryAfterMs;

    static void recordTransport(String outcome, int status, Long delay) {
        reason = outcome; transportStatus = status; retryAfterMs = delay;
        spicyQueryStatus = null; source = ""; spicyEnvelopeNoticePresent = false;
    }
    static String recordEnvelope(JsonElement envelope, JsonObject result) {
        spicyEnvelopeNoticePresent = SpicyQueryEnvelope.noticePresent(envelope);
        spicyQueryStatus = SpicyQueryEnvelope.status(result);
        transportStatus = 200; retryAfterMs = null; source = "";
        String failure = SpicyQueryEnvelope.failureReason(result);
        if (result == null) failure = "Spicy operation 0 missing";
        JsonElement data = result == null ? null : result.get("data");
        try {
            if (SpicyObjPack.isPackedPayload(data)) data = SpicyObjPack.unpack(data);
            if (data != null && data.isJsonObject()) {
                source = Json.optString(data.getAsJsonObject(), "source");
                if (source == null) source = "";
                if (Integer.valueOf(429).equals(spicyQueryStatus)
                        && "Spotify API error".equals(Json.optString(data.getAsJsonObject(), "error"))) {
                    failure = "Spicy rate-limited: Spicy upstream degraded / Spotify API error";
                }
            }
        } catch (RuntimeException ignored) { }
        reason = failure == null ? "ok" : failure;
        return failure;
    }
}
