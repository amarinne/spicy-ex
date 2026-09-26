package com.eza.spicyex.lyrics.catalog;

import com.eza.spicyex.lyrics.catalog.CatalogResolver.Resolution;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The complete write set one decision produces, plus the seat it resolves. A change is applied in
 * one transaction or not at all; nothing observable happens between its parts.
 */
public final class CatalogChange {
    /** A persisted rejection: one provider item on one track. */
    public static final class Rejection {
        public final CatalogSource.SourceId source;
        public final String providerItemId;
        public final long rejectedAtMs;

        public Rejection(CatalogSource.SourceId source, String providerItemId, long rejectedAtMs) {
            this.source = source;
            this.providerItemId = providerItemId == null ? "" : providerItemId;
            this.rejectedAtMs = rejectedAtMs;
        }
    }

    public final CatalogTrack track;
    public final List<CatalogCandidate> putCandidates;
    public final List<String> deleteCandidateIds;
    public final List<ProviderRecord> putProviders;
    public final List<Rejection> putRejections;
    /** New seat to persist; null leaves the stored seat untouched. */
    public final CatalogSelection selection;
    /** What renders after this change. */
    public final Resolution resolution;
    /** Short machine-readable outcome, e.g. {@code stored} or {@code rejected-item}. */
    public final String outcome;
    /** False when the decision refused the event; nothing is written. */
    public final boolean accepted;

    CatalogChange(CatalogTrack track, List<CatalogCandidate> putCandidates,
                  List<String> deleteCandidateIds, List<ProviderRecord> putProviders,
                  List<Rejection> putRejections, CatalogSelection selection,
                  Resolution resolution, String outcome, boolean accepted) {
        this.track = track;
        this.putCandidates = frozen(putCandidates);
        this.deleteCandidateIds = frozen(deleteCandidateIds);
        this.putProviders = frozen(putProviders);
        this.putRejections = frozen(putRejections);
        this.selection = selection;
        this.resolution = resolution;
        this.outcome = outcome == null ? "" : outcome;
        this.accepted = accepted;
    }

    static CatalogChange refused(Resolution current, String outcome) {
        return new CatalogChange(null, null, null, null, null, null, current, outcome, false);
    }

    public boolean writesNothing() {
        return track == null && putCandidates.isEmpty() && deleteCandidateIds.isEmpty()
                && putProviders.isEmpty() && putRejections.isEmpty() && selection == null;
    }

    private static <T> List<T> frozen(List<T> values) {
        return values == null || values.isEmpty() ? Collections.<T>emptyList()
                : Collections.unmodifiableList(new ArrayList<>(values));
    }
}
