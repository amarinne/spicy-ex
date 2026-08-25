package com.eza.spicyex.lyrics;

import com.eza.spicyex.lyrics.session.CanonicalBase;
import com.eza.spicyex.lyrics.session.CanonicalRow;
import com.eza.spicyex.lyrics.session.MeaningArtifact;
import com.eza.spicyex.lyrics.session.MeaningEntry;

import java.util.Collections;
import java.util.Set;

/** Selects one document-level translation artifact by authority, coverage, and usable text. */
final class MeaningDisplaySelector {
    private MeaningDisplaySelector() {
    }

    static MeaningArtifact select(CanonicalBase base, Set<String> requiredRows,
                                  MeaningArtifact ai, MeaningArtifact google) {
        return select(base, requiredRows, ai, google, false);
    }

    static MeaningArtifact selectWithFallback(CanonicalBase base, Set<String> requiredRows,
                                              MeaningArtifact ai, MeaningArtifact google) {
        return select(base, requiredRows, ai, google, true);
    }

    private static MeaningArtifact select(CanonicalBase base, Set<String> requiredRows,
                                          MeaningArtifact ai, MeaningArtifact google,
                                          boolean allowPartialFallback) {
        Set<String> required = requiredRows == null
                ? Collections.<String>emptySet() : requiredRows;
        Candidate aiCandidate = Candidate.of(base, required, ai);
        Candidate googleCandidate = Candidate.of(base, required, google);
        if (aiCandidate.complete) return ai;
        if (googleCandidate.complete) return google;
        if (allowPartialFallback) {
            if (aiCandidate.coverage >= googleCandidate.coverage
                    && aiCandidate.coverage > 0) return ai;
            if (googleCandidate.coverage > 0) return google;
        }
        return null;
    }

    static boolean isUsable(CanonicalBase base, Set<String> requiredRows,
                            MeaningArtifact artifact) {
        Set<String> required = requiredRows == null
                ? Collections.<String>emptySet() : requiredRows;
        return Candidate.of(base, required, artifact).coverage > 0;
    }

    private static final class Candidate {
        final int coverage;
        final boolean complete;

        private Candidate(int coverage, boolean complete) {
            this.coverage = coverage;
            this.complete = complete;
        }

        static Candidate of(CanonicalBase base, Set<String> required, MeaningArtifact artifact) {
            if (base == null || artifact == null || artifact.isEmpty() || !artifact.appliesTo(base)) {
                return new Candidate(0, false);
            }
            int coverage = 0;
            for (String rowId : required) {
                MeaningEntry entry = artifact.meaning(rowId);
                CanonicalRow row = base.row(rowId);
                if (entry == null || row == null || entry.text.trim().isEmpty()
                        || GoogleEnhancer.sameText(row.text, entry.text)) {
                    continue;
                }
                coverage++;
            }
            return new Candidate(coverage, !required.isEmpty()
                    && !artifact.partial && coverage == required.size());
        }
    }
}
