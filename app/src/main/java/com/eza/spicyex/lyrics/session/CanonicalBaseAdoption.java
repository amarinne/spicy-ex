package com.eza.spicyex.lyrics.session;

import com.eza.spicyex.lyrics.LyricQualityRanker;
import com.eza.spicyex.lyrics.LyricsDocument;

/**
 * Decides what a freshly fetched document means for the session's canonical base.
 *
 * <p>Source identity is the canonical digest, not the fetch that produced it. The same source
 * arriving twice (cache preview then network confirmation) is not a replacement and must not
 * invalidate a single derived artifact. A genuinely different source increments the source
 * revision, which is the only axis that invalidates artifacts tied to the old digest.
 *
 * <p>Replacement is additionally quality-gated: a lower-quality refetch (for example a native
 * static fallback arriving over retired-Spicy synced lyrics) must never overwrite the better
 * base, on screen or in the cache.
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

    /**
     * True when {@code incoming} may supersede the current base: anything supersedes no base,
     * nothing supersedes on an empty result, and otherwise the ranker's quality score decides —
     * equal quality refreshes, lower quality never overwrites higher.
     */
    public static boolean shouldSupersede(LyricsDocument current, LyricsDocument incoming) {
        if (incoming == null || incoming.lines == null || incoming.lines.isEmpty()) return false;
        if (current == null) return true;
        return LyricQualityRanker.score(incoming) >= LyricQualityRanker.score(current);
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
