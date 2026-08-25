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
}
