package com.eza.spicyex.lyrics.session;

/**
 * Full stale-result identity for one derived-layer run.
 *
 * <p>A result may be applied only when track URI, session generation, source revision, canonical
 * digest, layer configuration, credential revision, and run ID are all still current. A late result
 * remains cacheable under its own identity; it never publishes into a newer session.
 */
public final class LayerRunIdentity {
    public final String trackUri;
    public final int generation;
    public final int sourceRevision;
    public final String canonicalDigest;
    public final LayerKind layerKind;
    public final String layerConfigId;
    public final String credentialRevision;
    public final String runId;

    public LayerRunIdentity(String trackUri, int generation, int sourceRevision, String canonicalDigest,
                            LayerKind layerKind, String layerConfigId, String credentialRevision, String runId) {
        this.trackUri = Digests.nz(trackUri);
        this.generation = generation;
        this.sourceRevision = sourceRevision;
        this.canonicalDigest = Digests.nz(canonicalDigest);
        this.layerKind = layerKind;
        this.layerConfigId = Digests.nz(layerConfigId);
        this.credentialRevision = Digests.nz(credentialRevision);
        this.runId = Digests.nz(runId);
    }

    public static LayerRunIdentity forSession(LyricSession session, LayerKind kind, String layerConfigId,
                                              String credentialRevision, String runId) {
        SessionIdentity identity = session.identity;
        return new LayerRunIdentity(identity.trackUri, identity.generation, identity.sourceRevision,
                identity.canonicalDigest, kind, layerConfigId, credentialRevision, runId);
    }

    /** Cache identity for this run's artifact: canonical digest plus layer config, nothing else. */
    public String artifactCacheKey() {
        return layerKind.name().toLowerCase(java.util.Locale.ROOT)
                + "|" + canonicalDigest + "|" + layerConfigId
                + (credentialRevision.isEmpty() ? "" : "|cred=" + credentialRevision);
    }

    boolean matchesSourceOf(SessionIdentity identity) {
        return identity != null
                && trackUri.equals(identity.trackUri)
                && generation == identity.generation
                && sourceRevision == identity.sourceRevision
                && canonicalDigest.equals(identity.canonicalDigest);
    }
}
