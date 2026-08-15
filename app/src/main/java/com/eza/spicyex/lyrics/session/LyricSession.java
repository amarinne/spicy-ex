package com.eza.spicyex.lyrics.session;

/**
 * One current-track session: an immutable canonical base plus two independent derived layers.
 *
 * <p>Every mutation returns a new session, so the previous value stays valid for in-flight work and
 * publication can diff exactly which layer changed. Sound and Meaning share nothing but the base:
 * neither waits for the other, and neither's configuration invalidates the other's artifact.
 */
public final class LyricSession {
    public final SessionIdentity identity;
    public final CanonicalBase base;
    public final LayerState sound;
    public final LayerState meaning;

    private LyricSession(SessionIdentity identity, CanonicalBase base, LayerState sound, LayerState meaning) {
        this.identity = identity;
        this.base = base;
        this.sound = sound;
        this.meaning = meaning;
    }

    public static LyricSession of(CanonicalBase base, int generation) {
        return of(base, generation, 1);
    }

    public static LyricSession of(CanonicalBase base, int generation, int sourceRevision) {
        CanonicalBase safeBase = base == null
                ? new CanonicalBase("", "", "", "", "", "", 0L, null) : base;
        return new LyricSession(
                new SessionIdentity(safeBase.trackUri, generation, sourceRevision, safeBase.digest),
                safeBase, LayerState.absent(LayerKind.SOUND), LayerState.absent(LayerKind.MEANING));
    }

    public LayerState layer(LayerKind kind) {
        return kind == LayerKind.MEANING ? meaning : sound;
    }

    public LyricSession withSound(LayerState next) {
        return next == null || next == sound ? this : new LyricSession(identity, base, next, meaning);
    }

    public LyricSession withMeaning(LayerState next) {
        return next == null || next == meaning ? this : new LyricSession(identity, base, sound, next);
    }

    public LyricSession withLayer(LayerKind kind, LayerState next) {
        return kind == LayerKind.MEANING ? withMeaning(next) : withSound(next);
    }

    /**
     * Replaces the canonical base with a newer source revision. Each layer is kept only when its
     * artifact still applies to the new base — a source replacement invalidates incompatible
     * artifacts and nothing else.
     */
    public LyricSession withReplacedBase(CanonicalBase next) {
        if (next == null) return this;
        if (next.digest.equals(base.digest)) return this;
        LyricSession replaced = new LyricSession(
                new SessionIdentity(next.trackUri, identity.generation, identity.sourceRevision + 1, next.digest),
                next, LayerState.absent(LayerKind.SOUND), LayerState.absent(LayerKind.MEANING));
        return replaced
                .withSound(carryOver(sound, next))
                .withMeaning(carryOver(meaning, next));
    }

    /** Drops a layer whose configuration changed, leaving the other layer and the base untouched. */
    public LyricSession withLayerConfigChanged(LayerKind kind, String nextConfigId) {
        LayerState current = layer(kind);
        if (current.configId.equals(Digests.nz(nextConfigId))) return this;
        return withLayer(kind, LayerState.absent(kind));
    }

    /**
     * True when a completed run may publish into this session: full source identity plus the
     * layer's current config, credential revision, and run ID.
     */
    public boolean acceptsResult(LayerRunIdentity runIdentity) {
        return runIdentity != null
                && runIdentity.matchesSourceOf(identity)
                && layer(runIdentity.layerKind).acceptsRun(runIdentity);
    }

    private static LayerState carryOver(LayerState state, CanonicalBase next) {
        if (state == null || !state.hasArtifact()) return LayerState.absent(state == null
                ? LayerKind.SOUND : state.kind);
        // A carried-over artifact was produced against the previous canonical digest, so it can
        // never satisfy appliesTo() on the new base. Source replacement therefore always drops
        // derived artifacts, and re-derivation runs from the durable canonical cache without a
        // source refetch.
        return state.artifact.appliesTo(next)
                ? state.withArtifact(LayerStatus.RESTORED, state.artifact, state.runId)
                : state.dropped();
    }
}
