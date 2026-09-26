package com.eza.spicyex.lyrics.catalog;

import com.eza.spicyex.lyrics.catalog.CatalogSource.MatchMethod;
import com.eza.spicyex.lyrics.catalog.CatalogSource.SelectionMode;
import com.eza.spicyex.lyrics.catalog.CatalogSource.SourceId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * One pure automatic ranking rule for catalog candidates, replacing the competing heuristics in
 * {@code LyricQualityRanker}, {@code LyricsProviderChain}, and canonical-base adoption.
 *
 * <p>Comparison is ordered, never a blended score. Auto ranks lyric quality first: actual
 * timing class, then completeness, and only then match identity. A word-timed candidate from
 * any fetched source therefore outranks a line-timed one, and Source order mode instead
 * follows the configured priority strictly, falling back only when a higher source has no
 * data at all:
 *
 * <ol>
 *   <li>Actual timing: syllable, word, line, unsynced.</li>
 *   <li>Completeness: complete candidates outrank incomplete ones.</li>
 *   <li>Identity: exact Spotify ID, exact provider mapping, karaoke substitution, strong
 *       search. Weak matches are rejected, never ranked.</li>
 *   <li>Provider tie-break: Apple, Spotify native, AMLL, LRCLIB, QQ, NetEase.</li>
 *   <li>Capabilities: provider translation/transliteration, background vocals, duet, credits.</li>
 *   <li>Health: timing-healthy first, then confidence, duration fit, stable ID order.</li>
 * </ol>
 *
 * <p>Upgrade stability: the incumbent keeps its seat on ties and on equal digests, so callback
 * order and restarts cannot flip a persisted winner. A challenger needs a strictly better rank.
 * Manual mode pins the selected candidate; a missing pin renders the automatic best as a
 * clearly temporary fallback without clearing the pin.
 *
 * <p>Reason strings carry candidate IDs, ranks, and status only, never lyric bodies.
 */
public final class CatalogResolver {
    private CatalogResolver() {
    }

    /** Resolution outcome: the candidate to render plus the machine-readable reason. */
    public static final class Resolution {
        public final CatalogCandidate winner;
        /** e.g. {@code auto:apple|line|exact-provider|complete}, never lyric text. */
        public final String reason;
        /** True when the winner is standing in for a missing manual pin. */
        public final boolean temporary;

        Resolution(CatalogCandidate winner, String reason, boolean temporary) {
            this.winner = winner;
            this.reason = reason == null ? "" : reason;
            this.temporary = temporary;
        }
    }

    /**
     * Resolves which candidate renders. Pure and deterministic: equal inputs always elect the
     * same winner regardless of callback order.
     */
    public static Resolution resolve(List<CatalogCandidate> candidates, CatalogSelection selection) {
        List<CatalogCandidate> ranked = ranked(candidates);
        if (selection != null && selection.mode == SelectionMode.MANUAL) {
            CatalogCandidate pinned = byId(ranked, selection.candidateId);
            if (pinned != null) return new Resolution(pinned, describe("manual", pinned), false);
            if (!ranked.isEmpty()) {
                return new Resolution(ranked.get(0),
                        "manual-missing-temporary:" + describe("auto", ranked.get(0)), true);
            }
            return new Resolution(null, "manual-missing-empty", true);
        }
        if (ranked.isEmpty()) return new Resolution(null, "auto-empty", false);
        return new Resolution(ranked.get(0), describe("auto", ranked.get(0)), false);
    }

    /**
     * Resolves with hysteresis: the incumbent (already-rendered automatic winner) keeps its seat
     * against equal-ranked challengers and equal digests. A challenger takes over only with a
     * strictly better rank tuple — never an automatic downgrade.
     */
    public static Resolution resolveWithIncumbent(List<CatalogCandidate> candidates,
                                                  CatalogSelection selection,
                                                  CatalogCandidate incumbent) {
        Resolution fresh = resolve(candidates, selection);
        if (fresh.winner == null || incumbent == null) return fresh;
        if (selection != null && selection.mode == SelectionMode.MANUAL && !fresh.temporary) {
            return fresh;
        }
        if (fresh.temporary) return fresh;
        if (fresh.winner.candidateId.equals(incumbent.candidateId)) return fresh;
        if (fresh.winner.canonicalDigest.equals(incumbent.canonicalDigest)) {
            return new Resolution(incumbent, describe("auto-incumbent-same-digest", incumbent),
                    false);
        }
        if (!strictlyBetterRank(fresh.winner, incumbent)) {
            return new Resolution(incumbent, describe("auto-incumbent-hold", incumbent), false);
        }
        return fresh;
    }

    /**
     * Applies the runtime source policy before resolving Auto. Manual picks remain readable even
     * when their provider is disabled. In Source order mode, the first enabled source with a valid
     * candidate wins; quality ranking is used only between variants from that source.
     */
    public static Resolution resolveConfigured(List<CatalogCandidate> candidates,
                                               CatalogSelection selection,
                                               CatalogCandidate incumbent,
                                               List<SourceId> enabledOrder,
                                               boolean sourceOrderMode) {
        if (selection != null && selection.mode == SelectionMode.MANUAL) {
            return resolve(candidates, selection);
        }
        List<CatalogCandidate> enabled = new ArrayList<>();
        if (candidates != null && enabledOrder != null) {
            for (CatalogCandidate candidate : candidates) {
                if (candidate != null && enabledOrder.contains(candidate.sourceId)) {
                    enabled.add(candidate);
                }
            }
        }
        if (sourceOrderMode) {
            for (SourceId source : enabledOrder == null
                    ? Collections.<SourceId>emptyList() : enabledOrder) {
                List<CatalogCandidate> fromSource = new ArrayList<>();
                for (CatalogCandidate candidate : enabled) {
                    if (candidate.sourceId == source) fromSource.add(candidate);
                }
                List<CatalogCandidate> ranked = ranked(fromSource);
                if (!ranked.isEmpty()) {
                    CatalogCandidate winner = ranked.get(0);
                    if (incumbent != null && incumbent.sourceId == source
                            && !strictlyBetterRank(winner, incumbent)) {
                        winner = incumbent;
                    }
                    return new Resolution(winner, describe("source-order", winner), false);
                }
            }
            return new Resolution(null, "source-order-empty", false);
        }
        CatalogCandidate enabledIncumbent = null;
        if (incumbent != null) {
            for (CatalogCandidate candidate : enabled) {
                if (candidate.candidateId.equals(incumbent.candidateId)) {
                    enabledIncumbent = incumbent;
                    break;
                }
            }
        }
        return resolveWithIncumbent(enabled, CatalogSelection.auto(
                selection == null ? "" : selection.trackId), enabledIncumbent);
    }

    /** Ranked best-first; weak matches excluded, never null. */
    static List<CatalogCandidate> ranked(List<CatalogCandidate> candidates) {
        List<CatalogCandidate> out = new ArrayList<>();
        if (candidates != null) {
            for (CatalogCandidate candidate : candidates) {
                if (candidate != null && candidate.matchMethod != MatchMethod.WEAK) out.add(candidate);
            }
        }
        Collections.sort(out, new Comparator<CatalogCandidate>() {
            @Override public int compare(CatalogCandidate a, CatalogCandidate b) {
                return CatalogResolver.compare(a, b);
            }
        });
        return out;
    }

    /** Negative when {@code a} outranks {@code b}. */
    static int compare(CatalogCandidate a, CatalogCandidate b) {
        int diff = a.timingLevel.ordinal() - b.timingLevel.ordinal();
        if (diff != 0) return diff;
        if (a.complete != b.complete) return a.complete ? -1 : 1;
        diff = a.matchMethod.ordinal() - b.matchMethod.ordinal();
        if (diff != 0) return diff;
        diff = sourceRank(a.sourceId) - sourceRank(b.sourceId);
        if (diff != 0) return diff;
        diff = capabilityCount(b) - capabilityCount(a);
        if (diff != 0) return diff;
        if (a.timingHealthy != b.timingHealthy) return a.timingHealthy ? -1 : 1;
        diff = Double.compare(b.matchConfidence, a.matchConfidence);
        if (diff != 0) return diff;
        diff = Long.compare(Math.abs(a.durationDeltaMs), Math.abs(b.durationDeltaMs));
        if (diff != 0) return diff;
        return a.candidateId.compareTo(b.candidateId);
    }

    /**
     * True only when {@code challenger} beats the incumbent on the coarse rank tuple (timing,
     * completeness, identity, provider, capabilities, health). Confidence, duration fit, and row
     * order elect fresh winners but never dethrone a seated one: an equal-ranked result is not
     * a material quality improvement.
     */
    static boolean strictlyBetterRank(CatalogCandidate challenger, CatalogCandidate incumbent) {
        if (challenger.timingLevel.ordinal() != incumbent.timingLevel.ordinal()) {
            return challenger.timingLevel.ordinal() < incumbent.timingLevel.ordinal();
        }
        if (challenger.complete != incumbent.complete) return challenger.complete;
        if (challenger.matchMethod.ordinal() != incumbent.matchMethod.ordinal()) {
            return challenger.matchMethod.ordinal() < incumbent.matchMethod.ordinal();
        }
        if (sourceRank(challenger.sourceId) != sourceRank(incumbent.sourceId)) {
            return sourceRank(challenger.sourceId) < sourceRank(incumbent.sourceId);
        }
        if (capabilityCount(challenger) != capabilityCount(incumbent)) {
            return capabilityCount(challenger) > capabilityCount(incumbent);
        }
        if (challenger.timingHealthy != incumbent.timingHealthy) return challenger.timingHealthy;
        return false;
    }

    private static int sourceRank(SourceId source) {
        return source == null ? Integer.MAX_VALUE : source.ordinal();
    }

    private static int capabilityCount(CatalogCandidate candidate) {
        int count = 0;
        if (candidate.hasProviderTranslation) count++;
        if (candidate.hasProviderTransliteration) count++;
        if (candidate.hasBackgroundVocals) count++;
        if (candidate.hasDuet) count++;
        if (candidate.hasCredits) count++;
        return count;
    }

    private static CatalogCandidate byId(List<CatalogCandidate> ranked, String candidateId) {
        if (candidateId == null || candidateId.isEmpty()) return null;
        for (CatalogCandidate candidate : ranked) {
            if (candidateId.equals(candidate.candidateId)) return candidate;
        }
        return null;
    }

    private static String describe(String prefix, CatalogCandidate winner) {
        String source = winner.sourceId == null ? "unknown" : winner.sourceId.id;
        return prefix + ":" + source
                + "|" + winner.timingLevel.name().toLowerCase(java.util.Locale.ROOT)
                + "|" + winner.matchMethod.name().toLowerCase(java.util.Locale.ROOT)
                + "|" + (winner.complete ? "complete" : "incomplete")
                + "|" + winner.candidateId;
    }
}
