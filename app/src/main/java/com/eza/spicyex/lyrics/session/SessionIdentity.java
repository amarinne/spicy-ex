package com.eza.spicyex.lyrics.session;

/** Identity of one current-track session over one canonical source revision. */
public final class SessionIdentity {
    public final String trackUri;
    public final int generation;
    /** Increments only when the canonical base is replaced by a different source. */
    public final int sourceRevision;
    public final String canonicalDigest;

    public SessionIdentity(String trackUri, int generation, int sourceRevision, String canonicalDigest) {
        this.trackUri = Digests.nz(trackUri);
        this.generation = generation;
        this.sourceRevision = sourceRevision;
        this.canonicalDigest = Digests.nz(canonicalDigest);
    }

    public boolean sameSource(SessionIdentity other) {
        return other != null
                && trackUri.equals(other.trackUri)
                && generation == other.generation
                && sourceRevision == other.sourceRevision
                && canonicalDigest.equals(other.canonicalDigest);
    }
}
