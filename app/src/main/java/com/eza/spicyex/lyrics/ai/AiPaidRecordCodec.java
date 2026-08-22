package com.eza.spicyex.lyrics.ai;

import com.eza.spicyex.lyrics.session.LayerKind;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Serializes a paid record for the durable store, and reads it back defensively.
 *
 * <p>Reading is where the care goes. This payload outlives the process and survives app upgrades,
 * so a decode failure must produce {@code null} — a record we decline to trust — and never a
 * half-populated object that would be applied to a document or, worse, treated as a complete answer
 * that need not be bought again. Anything unrecognised is a record from a contract this build does
 * not speak, and the honest response is to ignore it rather than to guess.
 *
 * <p>The payload carries no credential and no provider response body: accepted item text, the
 * request bytes needed to resume a chunk, and accounting.
 */
final class AiPaidRecordCodec {

    private static final int PAYLOAD_VERSION = 1;

    private AiPaidRecordCodec() {
    }

    static String encode(AiPaidRecord record) {
        JsonObject root = new JsonObject();
        root.addProperty("v", PAYLOAD_VERSION);
        root.addProperty("layer", record.layer.name());
        root.addProperty("docDigest", record.docDigest);
        root.addProperty("configId", record.configId);
        root.addProperty("providerId", record.providerId);
        root.addProperty("modelName", record.modelName);
        root.addProperty("targetLang", record.targetLang);
        if (record.sourceLanguage != null) root.addProperty("sourceLanguage", record.sourceLanguage);
        if (record.pronunciationSystem != null) {
            root.addProperty("pronunciationSystem", record.pronunciationSystem);
        }
        root.addProperty("schemaVersion", record.schemaVersion);
        root.addProperty("chunkPlanVersion", record.chunkPlanVersion);
        root.addProperty("status", record.status.name());
        root.addProperty("inputTokens", record.inputTokens);
        root.addProperty("outputTokens", record.outputTokens);
        root.addProperty("usageEstimated", record.usageEstimated);
        root.addProperty("createdAtMs", record.createdAtMs);
        root.addProperty("lastAccessedAtMs", record.lastAccessedAtMs);

        JsonObject items = new JsonObject();
        for (Map.Entry<String, String> entry : record.items().entrySet()) {
            items.addProperty(entry.getKey(), entry.getValue());
        }
        root.add("items", items);

        JsonObject chunks = new JsonObject();
        for (Map.Entry<String, AiChunkRecord> entry : record.chunks().entrySet()) {
            chunks.add(entry.getKey(), encodeChunk(entry.getValue()));
        }
        root.add("chunks", chunks);
        return root.toString();
    }

    /** @return the decoded record, or null when the payload is absent, unreadable, or foreign */
    static AiPaidRecord decode(String payload) {
        if (payload == null || payload.isEmpty()) return null;
        try {
            JsonElement parsed = JsonParser.parseString(payload);
            if (!parsed.isJsonObject()) return null;
            JsonObject root = parsed.getAsJsonObject();
            if (intOf(root, "v", 0) != PAYLOAD_VERSION) return null;

            LayerKind layer = layerOf(stringOf(root, "layer"));
            if (layer == null) return null;

            AiPaidRecord record = new AiPaidRecord(layer, stringOf(root, "docDigest"),
                    stringOf(root, "configId"), stringOf(root, "providerId"),
                    stringOf(root, "modelName"), stringOf(root, "targetLang"),
                    optionalStringOf(root, "sourceLanguage"),
                    optionalStringOf(root, "pronunciationSystem"),
                    intOf(root, "schemaVersion", -1), intOf(root, "chunkPlanVersion", -1));

            AiPaidRecord.Status status = statusOf(stringOf(root, "status"));
            if (status == null) return null;
            record.status = status;
            record.inputTokens = intOf(root, "inputTokens", 0);
            record.outputTokens = intOf(root, "outputTokens", 0);
            record.usageEstimated = boolOf(root, "usageEstimated");
            record.createdAtMs = longOf(root, "createdAtMs");
            record.lastAccessedAtMs = longOf(root, "lastAccessedAtMs");

            if (root.has("items") && root.get("items").isJsonObject()) {
                for (Map.Entry<String, JsonElement> entry
                        : root.getAsJsonObject("items").entrySet()) {
                    if (!entry.getValue().isJsonPrimitive()) continue;
                    record.putItem(entry.getKey(), entry.getValue().getAsString());
                }
            }
            if (root.has("chunks") && root.get("chunks").isJsonObject()) {
                for (Map.Entry<String, JsonElement> entry
                        : root.getAsJsonObject("chunks").entrySet()) {
                    if (!entry.getValue().isJsonObject()) continue;
                    AiChunkRecord chunk = decodeChunk(entry.getValue().getAsJsonObject());
                    if (chunk == null) return null;
                    record.putChunk(entry.getKey(), chunk);
                }
            }
            return record;
        } catch (RuntimeException malformed) {
            // A stored record we cannot read is not a crash and not an empty answer: it is simply
            // not a hit, and the caller will treat it as one more question to buy.
            return null;
        }
    }

    private static JsonObject encodeChunk(AiChunkRecord chunk) {
        JsonObject out = new JsonObject();
        JsonArray ids = new JsonArray();
        for (String id : chunk.ids) ids.add(id);
        out.add("ids", ids);
        out.addProperty("requestJson", chunk.requestJson);
        out.addProperty("status", chunk.status.name());
        out.addProperty("attempts", chunk.attempts);
        out.addProperty("repairs", chunk.repairs);
        out.addProperty("inputTokens", chunk.inputTokens);
        out.addProperty("outputTokens", chunk.outputTokens);
        out.addProperty("usageEstimated", chunk.usageEstimated);
        if (chunk.failure != null) {
            JsonObject failure = new JsonObject();
            failure.addProperty("reason", chunk.failure.reason.name());
            failure.addProperty("status", chunk.failure.status);
            failure.addProperty("detail", chunk.failure.detail);
            out.add("failure", failure);
        }
        return out;
    }

    private static AiChunkRecord decodeChunk(JsonObject source) {
        List<String> ids = new ArrayList<>();
        if (source.has("ids") && source.get("ids").isJsonArray()) {
            for (JsonElement id : source.getAsJsonArray("ids")) {
                if (id.isJsonPrimitive()) ids.add(id.getAsString());
            }
        }
        AiChunkRecord chunk = new AiChunkRecord(ids, stringOf(source, "requestJson"));
        AiChunkRecord.Status status = chunkStatusOf(stringOf(source, "status"));
        if (status == null) return null;
        chunk.status = status;
        chunk.attempts = intOf(source, "attempts", 0);
        chunk.repairs = intOf(source, "repairs", 0);
        chunk.inputTokens = intOf(source, "inputTokens", 0);
        chunk.outputTokens = intOf(source, "outputTokens", 0);
        chunk.usageEstimated = boolOf(source, "usageEstimated");
        if (source.has("failure") && source.get("failure").isJsonObject()) {
            JsonObject failure = source.getAsJsonObject("failure");
            AiFailureReason reason = failureReasonOf(stringOf(failure, "reason"));
            if (reason == null) return null;
            chunk.failure = new AiChunkFailure(reason, intOf(failure, "status", 0),
                    stringOf(failure, "detail"));
        }
        return chunk;
    }

    private static LayerKind layerOf(String name) {
        for (LayerKind kind : LayerKind.values()) if (kind.name().equals(name)) return kind;
        return null;
    }

    private static AiPaidRecord.Status statusOf(String name) {
        for (AiPaidRecord.Status status : AiPaidRecord.Status.values()) {
            if (status.name().equals(name)) return status;
        }
        return null;
    }

    private static AiChunkRecord.Status chunkStatusOf(String name) {
        for (AiChunkRecord.Status status : AiChunkRecord.Status.values()) {
            if (status.name().equals(name)) return status;
        }
        return null;
    }

    private static AiFailureReason failureReasonOf(String name) {
        for (AiFailureReason reason : AiFailureReason.values()) {
            if (reason.name().equals(name)) return reason;
        }
        return null;
    }

    private static String stringOf(JsonObject source, String key) {
        JsonElement value = source.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }

    private static String optionalStringOf(JsonObject source, String key) {
        JsonElement value = source.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }

    private static int intOf(JsonObject source, String key, int fallback) {
        JsonElement value = source.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsInt() : fallback;
    }

    private static long longOf(JsonObject source, String key) {
        JsonElement value = source.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsLong() : 0L;
    }

    private static boolean boolOf(JsonObject source, String key) {
        JsonElement value = source.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsBoolean();
    }

    static String describe(AiPaidRecord record) {
        return record == null ? "none"
                : record.layer.name().toLowerCase(Locale.ROOT) + "/" + record.status.name();
    }
}
