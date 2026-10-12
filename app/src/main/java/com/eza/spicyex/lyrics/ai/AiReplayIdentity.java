package com.eza.spicyex.lyrics.ai;

import com.eza.spicyex.lyrics.session.Digests;
import com.google.gson.*;
import java.util.*;

/** Output compatibility, separate from the exact request identity and its Google draft. */
public final class AiReplayIdentity {
    /** Index projection for the ledger. Unsupported old artifacts remain untouched. */
    public static JsonObject index(String key, String wrapper) {
        try {
            JsonObject outer = JsonParser.parseString(wrapper).getAsJsonObject();
            AiPaidRecord record = AiPaidRecordCodec.decode(outer.get("payload").getAsString());
            AiReplayIdentity replay = fromRecord(record);
            if (replay == null || record.schemaVersion != AiContract.schemaFor(record.layer)
                    || record.chunkPlanVersion != AiContract.CHUNK_PLAN_VERSION
                    || !new HashSet<>(replay.rowIds).equals(record.items().keySet())) return null;
            com.eza.spicyex.lyrics.session.PaidArtifactIdentity identity = AiIdentity.recordIdentity(
                    record.layer, record.docDigest, record.providerId, record.modelName, record.configId);
            if (!identity.isComplete()
                    || !com.eza.spicyex.lyrics.session.AIPaidArtifactCache.matches(outer, identity)
                    || !com.eza.spicyex.lyrics.session.AIPaidArtifactCache.entryKey(identity).equals(key)) return null;
            JsonObject out = new JsonObject();
            out.addProperty("layer", record.layer.name());
            out.addProperty("configId", record.configId);
            out.addProperty("digest", replay.digest);
            out.addProperty("baseline", replay.baseline);
            return out;
        } catch (RuntimeException unsupported) { return null; }
    }
    public final String digest;
    public final boolean baseline;
    public final List<String> rowIds;
    private final JsonArray descriptors;

    private AiReplayIdentity(JsonArray descriptors, List<String> ids, boolean baseline) {
        this.descriptors = descriptors.deepCopy();
        this.rowIds = Collections.unmodifiableList(new ArrayList<>(ids));
        this.baseline = baseline;
        this.digest = Digests.sha256(descriptors.toString());
    }

    public static AiReplayIdentity of(List<AiLine> rows, boolean baseline) {
        JsonArray values = new JsonArray();
        List<String> ids = new ArrayList<>();
        for (AiLine row : rows) {
            if (!row.isSent()) continue;
            values.add(descriptor(row.sourceText, row.lineClass.token, AiVoiceHint.tokenOf(row.voice)));
            ids.add(row.id);
        }
        return new AiReplayIdentity(values, ids, baseline);
    }

    private static JsonObject descriptor(String text, String kind, String voice) {
        JsonObject value = new JsonObject();
        value.addProperty("textHash", Digests.sha256(text));
        value.addProperty("class", kind);
        value.addProperty("voice", voice);
        return value;
    }

    public JsonObject encode() {
        JsonObject value = new JsonObject();
        value.addProperty("version", 1);
        value.addProperty("baseline", baseline);
        value.add("rows", descriptors.deepCopy());
        JsonArray ids = new JsonArray();
        for (String id : rowIds) ids.add(id);
        value.add("ids", ids);
        return value;
    }

    public static AiReplayIdentity decode(JsonObject value) {
        if (value == null || !value.has("version") || value.get("version").getAsInt() != 1) return null;
        JsonArray rows = value.getAsJsonArray("rows");
        List<String> ids = new ArrayList<>();
        for (JsonElement id : value.getAsJsonArray("ids")) ids.add(id.getAsString());
        if (rows.size() != ids.size() || ids.isEmpty()
                || new HashSet<>(ids).size() != ids.size()) return null;
        for (int i = 0; i < rows.size(); i++) {
            JsonObject row = rows.get(i).getAsJsonObject();
            if (ids.get(i).isEmpty() || row.size() != 3
                    || !row.get("textHash").getAsString().matches("[0-9a-f]{64}")
                    || !row.has("class") || !row.has("voice")) return null;
        }
        return new AiReplayIdentity(rows, ids, value.get("baseline").getAsBoolean());
    }

    /** Backfills only complete legacy requests whose full ordered input is still available. */
    public static AiReplayIdentity fromRecord(AiPaidRecord record) {
        if (record == null || !record.isComplete()) return null;
        if (record.replay != null) return record.replay;
        LinkedHashMap<String, JsonObject> items = new LinkedHashMap<>();
        boolean baseline = false;
        try {
            for (AiChunkRecord chunk : record.chunks().values()) {
                if (chunk.requestJson.isEmpty()) continue;
                JsonObject request = JsonParser.parseString(chunk.requestJson).getAsJsonObject();
                JsonObject context = request.getAsJsonObject("context");
                if (context == null) return null;
                for (Map.Entry<String, JsonElement> entry : context.entrySet()) {
                    JsonElement field = entry.getValue();
                    if (!field.isJsonNull() && !field.isJsonArray()
                            && !field.getAsString().isEmpty()) return null;
                    if (field.isJsonArray() && field.getAsJsonArray().size() != 0) return null;
                }
                for (JsonElement element : request.getAsJsonArray("items")) {
                    JsonObject item = element.getAsJsonObject();
                    String id = item.get("id").getAsString();
                    JsonObject value = descriptor(item.get("s").getAsString(),
                            item.get("c").getAsString(), item.get("v").isJsonNull()
                                    ? null : item.get("v").getAsString());
                    JsonObject previous = items.putIfAbsent(id, value);
                    if (previous != null && !previous.equals(value)) return null;
                    baseline |= item.has("p");
                }
            }
            if (items.isEmpty() || !new ArrayList<>(items.keySet()).equals(
                    new ArrayList<>(record.items().keySet()))) return null;
            JsonArray rows = new JsonArray();
            for (JsonObject item : items.values()) rows.add(item);
            return new AiReplayIdentity(rows, new ArrayList<>(items.keySet()), baseline);
        } catch (RuntimeException unsupported) {
            return null;
        }
    }

    /** Readdresses every output occurrence; partial matches and changed voices cannot pass. */
    public AiPaidRecord project(AiPaidRecord record) {
        AiReplayIdentity saved = fromRecord(record);
        if (saved == null || baseline != saved.baseline || !digest.equals(saved.digest)
                || !new HashSet<>(saved.rowIds).equals(record.items().keySet())) return null;
        AiPaidRecord copy = AiPaidRecordCodec.decode(AiPaidRecordCodec.encode(record));
        if (copy == null) return null;
        Map<String, String> mapped = new LinkedHashMap<>();
        for (int i = 0; i < rowIds.size(); i++) mapped.put(rowIds.get(i), record.item(saved.rowIds.get(i)));
        copy.replaceItems(mapped);
        return copy;
    }
}
