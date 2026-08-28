package com.eza.spicyex.lyrics.ai;

import com.eza.spicyex.lyrics.session.LayerKind;

/**
 * Everything one call is configured with, immutable for the duration of that call.
 *
 * <p>Immutable on purpose: a configuration change starts a new run rather than mutating one in
 * flight, so a result can always be attributed to the exact configuration that produced it. The
 * runtime derives the per-attempt copy with {@link #forCall} rather than writing into this one.
 *
 * <p>No credential lives here. Credential handling arrives with the slice that needs it, and
 * nothing in this one can accidentally log or store one it never held.
 */
public final class AiProviderConfig {
    public final LayerKind layer;
    /** Normalized base URL for an OpenAI-compatible endpoint, or null. */
    public final String endpoint;
    public final String providerVersion;
    public final AiModelDescriptor model;
    /** Target language for Meaning, target orthography for Sound. */
    public final String targetLang;
    public final int promptVersion;
    public final int temperature;
    public final String contextMode;
    /** True when this attempt carries the versioned repair instruction. */
    public final boolean repair;
    /** True when this is an explicit quality revision over accepted output. */
    public final boolean iteration;
    /** True when Meaning receives a Google Translate draft in {@code p}. */
    public final boolean baselineRefinement;
    public final int maxOutputTokens;

    public AiProviderConfig(LayerKind layer, String endpoint, String providerVersion,
                            AiModelDescriptor model, String targetLang, int promptVersion,
                            boolean iteration) {
        this(layer, endpoint, providerVersion, model, targetLang, promptVersion, iteration, false);
    }

    public AiProviderConfig(LayerKind layer, String endpoint, String providerVersion,
                            AiModelDescriptor model, String targetLang, int promptVersion,
                            boolean iteration, boolean baselineRefinement) {
        this(layer, endpoint, providerVersion, model, targetLang, promptVersion,
                AiContract.TEMPERATURE, AiContract.CONTEXT_MODE, false, iteration,
                baselineRefinement, defaultOutputTokens(model));
    }

    private AiProviderConfig(LayerKind layer, String endpoint, String providerVersion,
                             AiModelDescriptor model, String targetLang, int promptVersion,
                             int temperature, String contextMode, boolean repair, boolean iteration,
                             boolean baselineRefinement, int maxOutputTokens) {
        this.layer = layer == null ? LayerKind.MEANING : layer;
        this.endpoint = endpoint;
        this.providerVersion = AiText.nz(providerVersion);
        this.model = model;
        this.targetLang = AiText.nz(targetLang);
        this.promptVersion = promptVersion;
        this.temperature = temperature;
        this.contextMode = AiText.nz(contextMode);
        this.repair = repair;
        this.iteration = iteration;
        this.baselineRefinement = baselineRefinement;
        this.maxOutputTokens = clampOutputTokens(model, maxOutputTokens);
    }

    /** The per-attempt copy: same configuration, this attempt's repair flag and output cap. */
    public AiProviderConfig forCall(boolean repair, int maxOutputTokens) {
        return new AiProviderConfig(layer, endpoint, providerVersion, model, targetLang,
                promptVersion, temperature, contextMode, repair, iteration, baselineRefinement,
                maxOutputTokens);
    }

    private static int defaultOutputTokens(AiModelDescriptor model) {
        return clampOutputTokens(model, AiContract.MAX_CONFIGURED_OUTPUT_TOKENS);
    }

    private static int clampOutputTokens(AiModelDescriptor model, int requested) {
        int modelLimit = model == null
                ? AiContract.MAX_CONFIGURED_OUTPUT_TOKENS : model.outputTokenLimit;
        int positive = requested > 0 ? requested : AiContract.MAX_CONFIGURED_OUTPUT_TOKENS;
        return Math.max(1, Math.min(Math.min(modelLimit, positive),
                AiContract.MAX_CONFIGURED_OUTPUT_TOKENS));
    }

    /**
     * The output cap for a call: the contract's per-request ceiling, or the model's own limit when
     * that is lower.
     */
    public int callOutputTokens() {
        return maxOutputTokens;
    }

    /** The system turn this configuration produces for the given attempt. */
    public String systemPrompt() {
        return AiContract.buildSystemPrompt(layer, targetLang, repair, iteration,
                baselineRefinement);
    }
}
