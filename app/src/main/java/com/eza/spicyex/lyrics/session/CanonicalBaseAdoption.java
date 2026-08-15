package com.eza.spicyex.lyrics.session;

/**
 * Decides what a freshly fetched document means for the session's canonical base.
 *
 * <p>Source identity is the canonical digest, not the fetch that produced it. The same source
 * arriving twice (cache preview then network confirmation) is not a replacement and must not
 * invalidate a single derived artifact. A genuinely different source increments the source
 * revision, which is the only axis that invalidates artifacts tied to the old digest.
 */
public final class CanonicalBaseAdoption {
    public enum Outcome {
        /** First base for this session. */
        ADOPT,
        /** A different source replaced the base; source revision increments. */
        REPLACE,
        /** Same canonical content; publish nothing and keep every derived artifact. */
        UNCHANGED
    }

    private CanonicalBaseAdoption() {
    }

    public static Outcome evaluate(boolean hasBase, String currentDigest, String incomingDigest) {
        String incoming = Digests.nz(incomingDigest);
        if (incoming.isEmpty()) return Outcome.UNCHANGED;
        if (!hasBase) return Outcome.ADOPT;
        return incoming.equals(Digests.nz(currentDigest)) ? Outcome.UNCHANGED : Outcome.REPLACE;
    }

    public static int nextSourceRevision(int currentRevision, Outcome outcome) {
        switch (outcome) {
            case ADOPT:
                return Math.max(1, currentRevision);
            case REPLACE:
                return Math.max(1, currentRevision) + 1;
            default:
                return currentRevision;
        }
    }
}
