package com.eza.spicyex.lyrics.ai;

import com.eza.spicyex.lyrics.session.LayerKind;
import com.eza.spicyex.lyrics.session.PaidArtifactIdentity;

/**
 * One run's identity: which document, which layer, and under exactly which configuration.
 *
 * <p>Everything here is part of what the answer depends on, which is why it is one object rather
 * than a handful of parameters. A field that changes makes the stored answer a different answer,
 * and the store must not serve it for this question — so the same object that configures the run
 * also derives its record key.
 */
public final class AiRunConfig {
    public final LayerKind layer;
    public final String docDigest;
    public final String configId;
    public final String providerId;
    public final String modelName;
    public final String targetLang;
    /** Sound only; null for Meaning. */
    public final String sourceLanguage;
    /** Sound only; null for Meaning. Pinyin and Jyutping are different answers to one question. */
    public final String pronunciationSystem;
    public final AiLyricContext context;
    public final AiProviderConfig provider;
    /** Normalized steering, or empty. Part of {@code configId}, so it is fixed for the run. */
    public final String instructions;

    public AiRunConfig(LayerKind layer, String docDigest, String configId, String providerId,
                       AiLyricContext context, AiProviderConfig provider, String instructions,
                       String sourceLanguage, String pronunciationSystem) {
        this.layer = layer == null ? LayerKind.MEANING : layer;
        this.docDigest = AiText.nz(docDigest);
        this.configId = AiText.nz(configId);
        this.providerId = AiText.nz(providerId);
        this.provider = provider;
        this.modelName = provider == null || provider.model == null ? "" : provider.model.name;
        this.targetLang = provider == null ? "" : provider.targetLang;
        this.context = AiLyricContext.normalize(context);
        this.instructions = AiContract.normalizeSteering(instructions);
        this.sourceLanguage = sourceLanguage;
        this.pronunciationSystem = pronunciationSystem;
    }

    /** Where this run's result is stored, and the only place it may be read back from. */
    public PaidArtifactIdentity recordIdentity() {
        return AiIdentity.recordIdentity(layer, docDigest, providerId, modelName, configId);
    }

    /**
     * True when a stored record answers this exact question.
     *
     * <p>The store key already covers layer, document, provider, model and configuration, so this
     * is the second gate rather than the first: it catches a record whose payload disagrees with
     * the key it was filed under, which is the shape a schema change or a corrupt write leaves
     * behind.
     */
    public boolean matches(AiPaidRecord record) {
        return record != null
                && record.layer == layer
                && record.docDigest.equals(docDigest)
                && record.configId.equals(configId)
                && record.schemaVersion == AiContract.schemaFor(layer)
                && record.chunkPlanVersion == AiContract.CHUNK_PLAN_VERSION;
    }
}
