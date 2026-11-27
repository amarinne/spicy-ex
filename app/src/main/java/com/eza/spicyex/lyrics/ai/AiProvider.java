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

    /** Receives a streamed call's accumulated text while it is still arriving. */
    interface StreamListener {
        /** The visible answer so far. Called again with the longer text as it grows. */
        void onText(String textSoFar);

        /** The reasoning trace so far, when the endpoint streams one. */
        default void onReasoning(String reasoningSoFar) {
        }
    }

    /**
     * {@link #generateChunk(AiProviderRequest, AiProviderConfig, AiSignal)}, streaming partial
     * text to {@code listener} where the adapter supports it. The returned result is the same
     * either way; an adapter without streaming simply never calls the listener.
     */
    default AiProviderResult generateChunk(AiProviderRequest request, AiProviderConfig config,
                                           AiSignal signal, StreamListener listener) {
        return generateChunk(request, config, signal);
    }

    /** Credential-free JSON body used by the live request monitor. */
    default String monitorPayload(AiProviderRequest request, AiProviderConfig config) {
        return request == null ? "" : request.toJson();
    }
}
