package com.eza.spicyex.lyrics.ai;

import com.eza.spicyex.lyrics.session.LayerKind;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything known about one paid document: what was asked, what came back, and what it cost.
 *
 * <p>This is the unit the owner paid for, so it is durable and it is never silently discarded.
 * Two properties follow from that and shape the whole class.
 *
 * <p><b>A partial record is worth keeping.</b> Chunks complete one at a time and each one was
 * billed on its own. A run interrupted by a track change has already spent money on the chunks it
 * finished, so those are stored and a later run resumes from them rather than paying twice. This is
 * why the record holds per-chunk state and not merely a finished/unfinished flag.
 *
 * <p><b>Accounting includes what failed.</b> A rejected response and a repair attempt both bill.
 * Tokens therefore accumulate across every attempt, and {@link #usageEstimated} records whether any
 * of those numbers had to be inferred rather than read from the provider — a cost display that
 * quietly rounded an unknown down to zero would be worse than one that admits it is approximate.
 */
public final class AiPaidRecord {

    public enum Status {
        /** Some chunks are done. Valid to resume; not valid to display. */
        PARTIAL,
        /** Every chunk validated. This is the only status that may be applied to a document. */
        COMPLETE,
        /** A chunk failed terminally. Retained for its accounting and its resumable chunks. */
        FAILED
    }

    public final LayerKind layer;
    public final String docDigest;
    public final String configId;
    public final String providerId;
    public final String modelName;
    /** Target language for Meaning, target orthography for Sound. */
    public final String targetLang;
    /** Sound only: the normalized source language the reading was produced for. */
    public final String sourceLanguage;
    /** Sound only: which pronunciation system produced it. Pinyin and Jyutping are not one answer. */
    public final String pronunciationSystem;
    public final int schemaVersion;
    public final int chunkPlanVersion;

    public Status status = Status.PARTIAL;
    public int inputTokens;
    public int outputTokens;
    public boolean usageEstimated;
    public long createdAtMs;
    public long lastAccessedAtMs;

    /** Accepted output by canonical row ID, in the order the rows were enumerated. */
    private final Map<String, String> items = new LinkedHashMap<>();
    /** Per-chunk state, keyed by the planner's stable chunk ID. */
    private final Map<String, AiChunkRecord> chunks = new LinkedHashMap<>();

    public AiPaidRecord(LayerKind layer, String docDigest, String configId, String providerId,
                        String modelName, String targetLang, String sourceLanguage,
                        String pronunciationSystem, int schemaVersion, int chunkPlanVersion) {
        this.layer = layer == null ? LayerKind.MEANING : layer;
        this.docDigest = AiText.nz(docDigest);
        this.configId = AiText.nz(configId);
        this.providerId = AiText.nz(providerId);
        this.modelName = AiText.nz(modelName);
        this.targetLang = AiText.nz(targetLang);
        this.sourceLanguage = sourceLanguage;
        this.pronunciationSystem = pronunciationSystem;
        this.schemaVersion = schemaVersion;
        this.chunkPlanVersion = chunkPlanVersion;
    }

    /** A fresh record for a plan about to run. */
    public static AiPaidRecord begin(AiRunConfig config, long nowMs) {
        AiPaidRecord record = new AiPaidRecord(config.layer, config.docDigest, config.configId,
                config.providerId, config.modelName, config.targetLang, config.sourceLanguage,
                config.pronunciationSystem, AiContract.schemaFor(config.layer),
                AiContract.CHUNK_PLAN_VERSION);
        record.createdAtMs = nowMs;
        record.lastAccessedAtMs = nowMs;
        return record;
    }

    public Map<String, String> items() {
        return Collections.unmodifiableMap(items);
    }

    public Map<String, AiChunkRecord> chunks() {
        return Collections.unmodifiableMap(chunks);
    }

    public String item(String rowId) {
        return items.get(AiText.nz(rowId));
    }

    public AiChunkRecord chunk(String chunkId) {
        return chunks.get(AiText.nz(chunkId));
    }

    public boolean isComplete() {
        return status == Status.COMPLETE;
    }

    public void putItem(String rowId, String text) {
        if (AiText.nz(rowId).isEmpty()) return;
        items.put(rowId, AiText.nz(text));
    }

    public void putChunk(String chunkId, AiChunkRecord record) {
        if (AiText.nz(chunkId).isEmpty() || record == null) return;
        chunks.put(chunkId, record);
    }

    /**
     * Folds one execution's accounting in, counting only what this attempt added.
     *
     * <p>A resumed chunk arrives carrying the totals of its earlier attempts, so the deltas are
     * taken against the record already held. Adding the raw totals would bill the earlier attempts
     * a second time on every resume.
     */
    public void account(AiChunkRecord previous, AiChunkRecord next) {
        if (next == null) return;
        int priorIn = previous == null ? 0 : previous.inputTokens;
        int priorOut = previous == null ? 0 : previous.outputTokens;
        inputTokens += next.inputTokens - priorIn;
        outputTokens += next.outputTokens - priorOut;
        usageEstimated |= next.usageEstimated;
    }

    /**
     * Reopens failed chunks so an explicit retry can resend exactly them.
     *
     * <p>Completed chunks are untouched: they are paid for and validated, and resending one would
     * be a second charge for an answer already held.
     */
    public void reopenFailedChunks() {
        for (AiChunkRecord chunk : chunks.values()) {
            if (chunk.status != AiChunkRecord.Status.FAILED) continue;
            chunk.status = AiChunkRecord.Status.PENDING;
            chunk.attempts = 0;
            chunk.repairs = 0;
            chunk.failure = null;
        }
        if (status == Status.FAILED) status = Status.PARTIAL;
    }

    /** Chunk IDs still owing a call, in plan order. */
    public List<String> outstandingChunkIds(AiChunkPlan plan) {
        List<String> out = new ArrayList<>();
        if (plan == null) return out;
        for (AiPlannedChunk chunk : plan.chunks) {
            AiChunkRecord held = chunks.get(chunk.id);
            if (held == null || !held.isComplete()) out.add(chunk.id);
        }
        return out;
    }

    /** True when every chunk of {@code plan} has validated output in this record. */
    public boolean satisfies(AiChunkPlan plan) {
        return plan != null && outstandingChunkIds(plan).isEmpty();
    }
}
