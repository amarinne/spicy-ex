package com.eza.spicyex.lyrics.session;

/**
 * Immutable state of one derived layer inside a session. Each transition returns a new instance,
 * so publication can diff layers without a whole-document rebuild.
 */
public final class LayerState {
    public final LayerKind kind;
    public final LayerStatus status;
    public final LayerAuthority authority;
    public final String configId;
    /** Credential generation for authorities that need one; empty for local/unauthenticated work. */
    public final String credentialRevision;
    /** Identity of the run that produced (or is producing) this state; empty when idle. */
    public final String runId;
    public final String artifactDigest;
    public final DerivedLayerArtifact artifact;
    public final LayerProvenance provenance;
    public final LayerFailure failure;
    /**
     * Rows the running AI request has already delivered, while it streams. Empty when no AI run
     * is streaming; the other displayed rows are still placeholders (Google preview or older
     * output) that the run will replace.
     */
    public final java.util.Set<String> aiDeliveredRows;

    private LayerState(LayerKind kind, LayerStatus status, LayerAuthority authority, String configId,
                       String credentialRevision, String runId, DerivedLayerArtifact artifact,
                       LayerProvenance provenance, LayerFailure failure) {
        this(kind, status, authority, configId, credentialRevision, runId, artifact, provenance,
                failure, java.util.Collections.<String>emptySet());
    }

    private LayerState(LayerKind kind, LayerStatus status, LayerAuthority authority, String configId,
                       String credentialRevision, String runId, DerivedLayerArtifact artifact,
                       LayerProvenance provenance, LayerFailure failure,
                       java.util.Set<String> aiDeliveredRows) {
        this.kind = kind;
        this.status = status;
        this.authority = authority;
        this.configId = Digests.nz(configId);
        this.credentialRevision = Digests.nz(credentialRevision);
        this.runId = Digests.nz(runId);
        this.artifact = artifact;
        this.artifactDigest = artifact == null ? "" : artifact.artifactDigest;
        this.provenance = provenance;
        this.failure = failure == null ? LayerFailure.NONE : failure;
        this.aiDeliveredRows = aiDeliveredRows == null || aiDeliveredRows.isEmpty()
                ? java.util.Collections.<String>emptySet()
                : java.util.Collections.unmodifiableSet(
                        new java.util.LinkedHashSet<>(aiDeliveredRows));
    }

    public static LayerState absent(LayerKind kind) {
        return new LayerState(kind, LayerStatus.ABSENT, LayerAuthority.DETERMINISTIC, "", "", "",
                null, null, LayerFailure.NONE);
    }

    public boolean hasArtifact() {
        return artifact != null && status.hasArtifact();
    }

    public LayerState processing(LayerAuthority nextAuthority, String nextConfigId,
                                 String nextCredentialRevision, String nextRunId) {
        return new LayerState(kind, LayerStatus.PROCESSING, nextAuthority, nextConfigId,
                nextCredentialRevision, nextRunId, artifact, provenance, LayerFailure.NONE);
    }

    public LayerState withArtifact(LayerStatus nextStatus, DerivedLayerArtifact nextArtifact, String nextRunId) {
        if (nextArtifact == null) return this;
        return new LayerState(kind, nextStatus, nextArtifact.provenance == null
                ? authority : nextArtifact.provenance.authority,
                nextArtifact.configId, credentialRevision, Digests.nz(nextRunId), nextArtifact,
                nextArtifact.provenance, LayerFailure.NONE);
    }

    /** Folds a bounded delta into the current artifact, keeping status and identity. */
    public LayerState withDelta(DerivedLayerArtifact delta, LayerStatus nextStatus) {
        if (delta == null) return this;
        DerivedLayerArtifact next;
        if (artifact == null) {
            next = delta;
        } else if (delta.partial
                && delta.provenance != null && delta.provenance.authority == LayerAuthority.AI
                && !delta.configId.equals(artifact.configId)) {
            // Streamed AI rows over a Google preview or older output: show each row as it lands.
            next = artifact.overlaidBy(delta);
        } else {
            next = artifact.mergedWith(delta);
        }
        java.util.Set<String> delivered = aiDeliveredRows;
        if (delta.partial && delta.provenance != null
                && delta.provenance.authority == LayerAuthority.AI) {
            delivered = new java.util.LinkedHashSet<>(aiDeliveredRows);
            for (LayerEntry entry : delta.allEntries()) delivered.add(entry.rowId());
        }
        if (next == artifact && delivered == aiDeliveredRows) return this;
        return new LayerState(kind, nextStatus, authority, next.configId, credentialRevision, runId,
                next, next.provenance, LayerFailure.NONE, delivered);
    }

    /**
     * Records a failed run. A previously accepted artifact stays displayed — the layer falls back
     * to {@link LayerStatus#CACHED} and {@link #failure} says why the refresh did not land. Only a
     * layer with nothing to show becomes {@link LayerStatus#FAILED}.
     */
    public LayerState failed(LayerFailure nextFailure) {
        LayerStatus nextStatus = artifact == null ? LayerStatus.FAILED : LayerStatus.CACHED;
        return new LayerState(kind, nextStatus, authority, configId, credentialRevision, "",
                artifact, provenance, nextFailure);
    }

    /** Lands a completed artifact and an orthogonal warning in one immutable transition. */
    public LayerState settled(DerivedLayerArtifact nextArtifact, LayerFailure nextFailure) {
        DerivedLayerArtifact landed = nextArtifact == null ? artifact : nextArtifact;
        LayerState next = landed == null
                ? dropped()
                : withArtifact(LayerStatus.READY, landed, "");
        return nextFailure != null && nextFailure.isFailure() ? next.failed(nextFailure) : next;
    }

    /** Drops the artifact and all run identity, e.g. after an incompatible base or config change. */
    public LayerState dropped() {
        return absent(kind);
    }

    /** True when a completed run under {@code identity} may still publish into this state. */
    public boolean acceptsRun(LayerRunIdentity identity) {
        return identity != null
                && identity.layerKind == kind
                && identity.layerConfigId.equals(configId)
                && identity.credentialRevision.equals(credentialRevision)
                && identity.runId.equals(runId);
    }
}
