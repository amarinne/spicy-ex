package com.eza.spicyex.lyrics.ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One chunk's outcome.
 *
 * <p>The record and the accounting come back on both paths. A failed chunk still spent money, and
 * an execution that reported cost only on success would understate every bad run.
 */
public final class AiChunkExecution {
    public final boolean ok;
    public final List<AiResponseItem> items;
    public final AiChunkRecord record;
    public final AiChunkFailure failure;
    /** Tokens accounted for this execution, reported or conservatively estimated. */
    public final long accountedTokens;

    private AiChunkExecution(boolean ok, List<AiResponseItem> items, AiChunkRecord record,
                             AiChunkFailure failure, long accountedTokens) {
        this.ok = ok;
        this.items = Collections.unmodifiableList(new ArrayList<>(
                items == null ? Collections.<AiResponseItem>emptyList() : items));
        this.record = record;
        this.failure = failure;
        this.accountedTokens = accountedTokens;
    }

    static AiChunkExecution accepted(List<AiResponseItem> items, AiChunkRecord record,
                                     long accountedTokens) {
        return new AiChunkExecution(true, items, record, null, accountedTokens);
    }

    static AiChunkExecution rejected(AiChunkFailure failure, AiChunkRecord record,
                                     long accountedTokens) {
        return new AiChunkExecution(false, null, record, failure, accountedTokens);
    }
}
