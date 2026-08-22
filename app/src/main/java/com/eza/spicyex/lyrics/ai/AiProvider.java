package com.eza.spicyex.lyrics.ai;

/**
 * One backend that can answer a chunk.
 *
 * <p>{@code generateChunk} is the normative name, because the same adapter serves both derived
 * layers: the desktop fork's historical {@code translateChunk} describes only half of what it does.
 *
 * <p>An implementation owns transport and nothing else. It does not decide retries, count attempts,
 * strip fences, or validate items — the runtime and the shared reader do, once, so every provider
 * gets identical treatment.
 */
public interface AiProvider {
    /** Stable id recorded in provenance and in the record key. */
    String id();

    /** Discovery, filtered to models this adapter can actually use. */
    AiModelListResult listModels(AiSignal signal);

    /**
     * Performs exactly one call. Never retries: two attempts per chunk is the runtime's budget to
     * spend, and an adapter that quietly retried would double the bill against it.
     */
    AiProviderResult generateChunk(AiProviderRequest request, AiProviderConfig config,
                                   AiSignal signal);

    /** Credential-free JSON body used by the live request monitor. */
    default String monitorPayload(AiProviderRequest request, AiProviderConfig config) {
        return request == null ? "" : request.toJson();
    }
}
