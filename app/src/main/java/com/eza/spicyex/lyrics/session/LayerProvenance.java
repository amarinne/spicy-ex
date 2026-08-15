package com.eza.spicyex.lyrics.session;

/** Who produced a derived artifact and under which contract. Never contains lyric text. */
public final class LayerProvenance {
    public final LayerAuthority authority;
    /** Stable processor/backend identifier, e.g. {@code "local-romanizer"} or {@code "google"}. */
    public final String producerId;
    /** Processor or prompt contract identity; changes when the producer's output shape changes. */
    public final String contractId;
    /**
     * Model that produced the artifact. Empty for deterministic and machine authorities, which have
     * no model choice; an AI artifact needs it, because the same prompt on a different model is a
     * different result the owner paid separately for.
     */
    public final String modelId;
    public final long producedAtMs;

    public LayerProvenance(LayerAuthority authority, String producerId, String contractId, long producedAtMs) {
        this(authority, producerId, contractId, "", producedAtMs);
    }

    public LayerProvenance(LayerAuthority authority, String producerId, String contractId,
                           String modelId, long producedAtMs) {
        this.authority = authority == null ? LayerAuthority.DETERMINISTIC : authority;
        this.producerId = Digests.nz(producerId);
        this.contractId = Digests.nz(contractId);
        this.modelId = Digests.nz(modelId);
        this.producedAtMs = producedAtMs;
    }

    String digestPayload() {
        return authority.name() + Digests.SEP + producerId + Digests.SEP + contractId
                + Digests.SEP + modelId;
    }
}
