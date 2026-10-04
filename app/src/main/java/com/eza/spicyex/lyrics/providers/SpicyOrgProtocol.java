package com.eza.spicyex.lyrics.providers;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.Request;

/** The public v1 API never uses Spotify credentials or the retired query transport. */
public final class SpicyOrgProtocol {
    private SpicyOrgProtocol() {}

    public static Request request(String trackId, String clientKey) {
        if (trackId == null || !trackId.matches("[A-Za-z0-9]{22}")) {
            throw new IllegalArgumentException("Invalid Spotify track ID");
        }
        if (clientKey == null || !clientKey.matches("sl_pk_[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("Spicy Lyrics client key required");
        }
        return new Request.Builder().url("https://api.spicylyrics.org/v1/lyrics/" + trackId)
                .header("Authorization", "Bearer " + clientKey)
                .header("Accept", "application/json")
                .header("User-Agent", "SpicyEX-Android/1.0")
                .get().build();
    }

    public static JsonObject body(String raw, String trackId) {
        JsonObject envelope = JsonParser.parseString(raw).getAsJsonObject();
        if (!envelope.has("Status") || envelope.get("Status").getAsInt() != 200
                || !envelope.has("Body") || !envelope.get("Body").isJsonObject()) {
            throw new IllegalArgumentException("Invalid Spicy Lyrics response");
        }
        JsonObject body = envelope.getAsJsonObject("Body");
        if (!body.has("id") || !trackId.equals(body.get("id").getAsString())) {
            throw new IllegalArgumentException("Spicy Lyrics track mismatch");
        }
        return body;
    }

    /** Error bodies can contain untrusted text. Expose fixed status messages only. */
    public static String error(int status) {
        String detail;
        switch (status) {
            case 401: detail = "client key invalid or revoked"; break;
            case 403: detail = "application access denied"; break;
            case 404: detail = "no lyrics"; break;
            case 429: detail = "rate limited"; break;
            default: detail = "service unavailable";
        }
        return "Spicy Lyrics HTTP " + status + ": " + detail;
    }

    /** Only the documented machine code can confirm termination; status and message cannot. */
    public static String terminationCode(int status, String raw) {
        if (status != 401 && status != 403) return "";
        try {
            JsonObject envelope = JsonParser.parseString(raw).getAsJsonObject();
            if (!envelope.has("Status") || !envelope.get("Status").isJsonPrimitive()
                    || !envelope.get("Status").getAsJsonPrimitive().isNumber()
                    || envelope.get("Status").getAsDouble() != status
                    || !envelope.has("Type") || !envelope.get("Type").isJsonPrimitive()
                    || !envelope.get("Type").getAsJsonPrimitive().isString()
                    || !"object".equals(envelope.get("Type").getAsString())
                    || !envelope.has("Body") || !envelope.get("Body").isJsonObject()) return "";
            com.google.gson.JsonElement error = envelope.getAsJsonObject("Body").get("error");
            if (error == null || !error.isJsonPrimitive()
                    || !error.getAsJsonPrimitive().isString()) return "";
            String code = error.getAsString();
            return terminatesAccess(code) ? code : "";
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    public static boolean terminatesAccess(String stableCode) {
        return "key_revoked".equals(stableCode) || "application_suspended".equals(stableCode)
                || "user_suspended".equals(stableCode) || "application_deleted".equals(stableCode);
    }

    /** Retry-After and RateLimit-Reset are relative seconds, never timestamps. */
    public static long seconds(String header) {
        try {
            return Math.min(7L * 24 * 60 * 60, Math.max(0, Long.parseLong(header)));
        } catch (RuntimeException ignored) {
            return 0;
        }
    }
}
