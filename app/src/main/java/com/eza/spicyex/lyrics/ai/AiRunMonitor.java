package com.eza.spicyex.lyrics.ai;

/** Receives the credential-free JSON body immediately before each provider dispatch. */
public interface AiRunMonitor {
    void onAttempt(String chunkId, int attempt, String wirePayload);
}
