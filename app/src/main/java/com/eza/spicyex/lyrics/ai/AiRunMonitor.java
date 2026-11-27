package com.eza.spicyex.lyrics.ai;

/** Receives the credential-free JSON body immediately before each provider dispatch. */
public interface AiRunMonitor {
    void onAttempt(String chunkId, int attempt, String wirePayload);

    /**
     * The reasoning trace an attempt came back with, after it returned.
     *
     * <p>Separate from {@link #onAttempt} because it arrives at the other end of the call and only
     * sometimes: a non-thinking model, or an endpoint that withholds the trace, simply never calls
     * this. Defaulted to nothing so a monitor that only watches outbound bodies stays a lambda.
     */
    default void onReasoning(String chunkId, int attempt, String reasoning) {
    }

    /**
     * Rows of a streamed attempt that already pass the row checks, while the call is still
     * arriving. Provisional: the final validation of the whole answer still decides what is kept
     * and stored. Called again with the longer list as more lines finish.
     */
    default void onPartialRows(String chunkId, java.util.List<AiResponseItem> rows) {
    }

    /** Count of streamed rows received so far in this chunk vs rows requested in this chunk. */
    default void onStreamProgress(String chunkId, int receivedRows, int totalRows) {
    }
}
