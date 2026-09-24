package com.eza.spicyex.lyrics;

import com.google.gson.*;

/** Selects the requested operation before inspecting any data or metadata. */
final class SpicyQueryEnvelope {
    private SpicyQueryEnvelope() {}

    static JsonObject result(JsonElement root) {
        if (root == null || !root.isJsonObject()) return null;
        JsonArray queries = Json.optArray(root.getAsJsonObject(), "queries");
        if (queries == null) return null;
        JsonObject selected = null;
        for (JsonElement item : queries) {
            if (!item.isJsonObject()) continue;
            JsonObject job = item.getAsJsonObject();
            JsonElement id = job.get("operationId");
            if (id != null && id.isJsonPrimitive() && id.getAsJsonPrimitive().isString()
                    && "0".equals(id.getAsString())) {
                selected = Json.optObject(job, "result");
            }
        }
        // The official client identifies the lyrics operation as 0, but older/server-side
        // envelopes have omitted or changed that metadata. Result presence is authoritative;
        // a policy notice or unrelated entry has no result object and is skipped above.
        return selected;
    }

    static boolean noticePresent(JsonElement root) {
        if (root == null || !root.isJsonObject()) return false;
        JsonArray queries = Json.optArray(root.getAsJsonObject(), "queries");
        if (queries == null) return false;
        for (JsonElement item : queries) {
            if (item.isJsonObject() && item.getAsJsonObject().has("_notice")) return true;
        }
        return false;
    }

    static Integer status(JsonObject result) {
        if (result == null) return null;
        try {
            JsonElement value = Json.optElement(result, "httpStatus", "status");
            return value == null ? null : value.getAsInt();
        } catch (RuntimeException ignored) { return null; }
    }

    static String failureReason(JsonObject result) {
        Integer status = status(result);
        if (status == null || status == 200) return null;
        if (status == 429) return "Apple Music rate-limited / upstream degraded";
        if (status == 401) return "Apple Music auth rejected HTTP 401";
        if (status == 404) return "Apple Music no-match";
        if (status == 503) return "Apple Music queued";
        return "Apple Music upstream-error HTTP " + status;
    }
}
