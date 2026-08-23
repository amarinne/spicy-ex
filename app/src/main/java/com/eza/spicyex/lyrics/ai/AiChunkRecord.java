package com.eza.spicyex.lyrics.ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What is known about one chunk: what was asked, how often, at what cost, and how it ended.
 *
 * <p>It holds the serialized request rather than a reference to the plan, which is what makes a
 * resume honest — a later attempt replays the same bytes instead of re-planning a document that may
 * meanwhile have been re-enumerated. The one exception is explicit {@link Status#REPLANNED} state:
 * its request reached the true ceiling and must never be replayed; deterministic child records own
 * the remaining work instead.
 *
 * <p>Tokens accumulate across attempts including failed and repaired ones, because those bill too.
 */
public final class AiChunkRecord {
    public enum Status {
        PENDING,
        /**
         * The original request reached the true output ceiling and was replaced by deterministic
         * hierarchical child chunks. The billed parent attempt remains here for accounting; a
         * resume runs only unfinished children and never resends this request.
         */
        REPLANNED,
        /** Every requested row validated. */
        COMPLETE,
        /**
         * Finished with some rows unsatisfied after the targeted re-ask; the kept rows are valid
         * and stored, the rest fall back to the baseline layer. Terminal like {@link #COMPLETE} —
         * a retry must not re-bill the rows that succeeded — but it is not complete: a record
         * holding this status never satisfies a plan's reuse gate.
         */
        COMPLETED_FALLBACK,
        FAILED
    }

    public final List<String> ids;
    public final String requestJson;
    public Status status;
    public int attempts;
    public int repairs;
    public int inputTokens;
    public int outputTokens;
    /** True once any attempt's usage had to be estimated rather than read from the provider. */
    public boolean usageEstimated;
    public AiChunkFailure failure;

    public AiChunkRecord(List<String> ids, String requestJson) {
        this.ids = Collections.unmodifiableList(
                new ArrayList<>(ids == null ? Collections.<String>emptyList() : ids));
        this.requestJson = AiText.nz(requestJson);
        this.status = Status.PENDING;
    }

    public static AiChunkRecord forChunk(AiPlannedChunk chunk) {
        return new AiChunkRecord(chunk.itemIds(), chunk.requestJson);
    }

    /** A detached copy, so a run in flight never mutates the record a caller already holds. */
    public AiChunkRecord copy() {
        AiChunkRecord copy = new AiChunkRecord(ids, requestJson);
        copy.status = status;
        copy.attempts = attempts;
        copy.repairs = repairs;
        copy.inputTokens = inputTokens;
        copy.outputTokens = outputTokens;
        copy.usageEstimated = usageEstimated;
        copy.failure = failure;
        return copy;
    }

    public boolean isComplete() {
        return status == Status.COMPLETE;
    }

    /** True when this chunk owes no further calls, whether fully or with fallback rows. */
    public boolean isTerminal() {
        return status == Status.COMPLETE || status == Status.COMPLETED_FALLBACK;
    }
}
