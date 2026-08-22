package com.eza.spicyex.lyrics.ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The whole document's plan.
 *
 * <p>Carries its planner version because the threshold and boundary rules govern what was actually
 * sent: a record produced under one set of boundaries does not answer a request planned under
 * another, so the version is part of cache identity.
 */
public final class AiChunkPlan {
    public final int version;
    public final List<AiPlannedChunk> chunks;
    /** Every enumerable row, sent or not. */
    public final int enumerableRows;
    public final int canonicalSourceUtf8Bytes;

    AiChunkPlan(int version, List<AiPlannedChunk> chunks, int enumerableRows,
                int canonicalSourceUtf8Bytes) {
        this.version = version;
        this.chunks = Collections.unmodifiableList(new ArrayList<>(chunks));
        this.enumerableRows = enumerableRows;
        this.canonicalSourceUtf8Bytes = canonicalSourceUtf8Bytes;
    }

    /** True when nothing needs sending — a document of structural rows costs no call. */
    public boolean isEmpty() {
        return chunks.isEmpty();
    }
}
