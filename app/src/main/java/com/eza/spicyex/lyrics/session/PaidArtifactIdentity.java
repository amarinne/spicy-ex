package com.eza.spicyex.lyrics.session;

import java.util.Locale;

/**
 * Storage identity for an accepted paid AI artifact.
 *
 * <p>Everything a paid result depends on is in the key: the canonical base it was derived from,
 * which layer it fills, and the provider, model, and prompt contract that produced it. Change any
 * one of them and the answer is a different artifact, so a stored one is never served for a request
 * it does not answer, and a new one never overwrites it.
 *
 * <p>Carries no lyric text and no credential material.
 */
public final class PaidArtifactIdentity {
    public final LayerKind layerKind;
    public final String canonicalDigest;
    public final String providerId;
    public final String modelId;
    public final String promptContractId;

    public PaidArtifactIdentity(LayerKind layerKind, String canonicalDigest, String providerId,
                                String modelId, String promptContractId) {
        this.layerKind = layerKind;
        this.canonicalDigest = Digests.nz(canonicalDigest);
        this.providerId = Digests.nz(providerId);
        this.modelId = Digests.nz(modelId);
        this.promptContractId = Digests.nz(promptContractId);
    }

    /**
     * Identity of the artifact itself, taken from what the producing run recorded rather than from
     * anything recomputed at write time.
     *
     * @return null when the artifact is not AI-authored or its provenance is too thin to address
     */
    public static PaidArtifactIdentity forArtifact(DerivedLayerArtifact artifact) {
        if (artifact == null || artifact.provenance == null) return null;
        if (artifact.provenance.authority != LayerAuthority.AI) return null;
        PaidArtifactIdentity identity = new PaidArtifactIdentity(artifact.kind,
                artifact.canonicalDigest, artifact.provenance.producerId,
                artifact.provenance.modelId, artifact.provenance.contractId);
        return identity.isComplete() ? identity : null;
    }

    /** False when any component is missing, which makes the artifact unaddressable. */
    public boolean isComplete() {
        return layerKind != null && !canonicalDigest.isEmpty() && !providerId.isEmpty()
                && !modelId.isEmpty() && !promptContractId.isEmpty();
    }

    /** Readable storage key. The store hashes it; it stays legible here so tests can assert it. */
    public String storageKey() {
        return "paid|" + (layerKind == null ? "none" : layerKind.name().toLowerCase(Locale.ROOT))
                + "|" + canonicalDigest
                + "|" + providerId
                + "|" + modelId
                + "|" + promptContractId;
    }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof PaidArtifactIdentity)) return false;
        return storageKey().equals(((PaidArtifactIdentity) other).storageKey());
    }

    @Override public int hashCode() {
        return storageKey().hashCode();
    }

    /** Safe to log: identity components only, never lyric text. */
    @Override public String toString() {
        return storageKey();
    }
}
