package com.eza.spicyex.lyrics.catalog;

import com.eza.spicyex.lyrics.catalog.CatalogSource.MatchMethod;
import com.eza.spicyex.lyrics.catalog.CatalogSource.SourceId;
import com.eza.spicyex.lyrics.catalog.CatalogSource.TimingLevel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * One stored lyric candidate. A track may hold several, including several versions from one
 * provider; the stable candidate identity (not the provider name alone) is what selection pins.
 */
public final class CatalogCandidate {
    public final String candidateId;
    public final String trackId;
    public final SourceId sourceId;
    public final String providerItemId;
    public final MatchMethod matchMethod;
    public final double matchConfidence;
    public final long durationDeltaMs;
    public final TimingLevel timingLevel;
    public final boolean complete;
    public final boolean timingHealthy;
    public final boolean hasProviderTranslation;
    public final boolean hasProviderTransliteration;
    public final boolean hasBackgroundVocals;
    public final boolean hasDuet;
    public final boolean hasCredits;
    public final String canonicalDigest;
    /** Immutable normalized document, encoded by {@link CatalogCodec}. */
    public final String normalizedDocument;
    /**
     * Provider-supplied transliteration baseline, one entry per document line, encoded by
     * {@link CatalogCodec}. Owned by the candidate: generated Sound readings live in the
     * digest-keyed Sound artifact and never here.
     */
    public final String providerTransliteration;
    /** Compressed raw provider payload, encoded by {@link CatalogCodec}. May be empty. */
    public final byte[] rawPayload;
    public final int parserRevision;
    public final int adapterRevision;
    public final long fetchedAtMs;

    public CatalogCandidate(String candidateId, String trackId, SourceId sourceId,
                            String providerItemId, MatchMethod matchMethod, double matchConfidence,
                            long durationDeltaMs, TimingLevel timingLevel, boolean complete,
                            boolean timingHealthy, boolean hasProviderTranslation,
                            boolean hasProviderTransliteration, boolean hasBackgroundVocals,
                            boolean hasDuet, boolean hasCredits, String canonicalDigest,
                            String normalizedDocument, String providerTransliteration,
                            byte[] rawPayload, int parserRevision, int adapterRevision,
                            long fetchedAtMs) {
        this.candidateId = nz(candidateId);
        this.trackId = nz(trackId);
        this.sourceId = sourceId;
        this.providerItemId = nz(providerItemId);
        this.matchMethod = matchMethod == null ? MatchMethod.STRONG_SEARCH : matchMethod;
        this.matchConfidence = Math.min(1.0, Math.max(0.0, matchConfidence));
        this.durationDeltaMs = durationDeltaMs;
        this.timingLevel = timingLevel == null ? TimingLevel.UNSYNCED : timingLevel;
        this.complete = complete;
        this.timingHealthy = timingHealthy;
        this.hasProviderTranslation = hasProviderTranslation;
        this.hasProviderTransliteration = hasProviderTransliteration;
        this.hasBackgroundVocals = hasBackgroundVocals;
        this.hasDuet = hasDuet;
        this.hasCredits = hasCredits;
        this.canonicalDigest = nz(canonicalDigest);
        this.normalizedDocument = nz(normalizedDocument);
        this.providerTransliteration = nz(providerTransliteration);
        this.rawPayload = rawPayload == null ? new byte[0] : rawPayload.clone();
        this.parserRevision = Math.max(0, parserRevision);
        this.adapterRevision = Math.max(0, adapterRevision);
        this.fetchedAtMs = Math.max(0L, fetchedAtMs);
    }

    /**
     * Stable identity for one provider item and document version. A refreshed fetch of the same
     * provider item keeps this ID only when the digest is unchanged; new content is a new row
     * that needs (usually automatic) selection.
     */
    public static String stableId(String trackId, SourceId sourceId, String providerItemId,
                                  String canonicalDigest) {
        String track = trackId == null || trackId.isEmpty() ? "-" : trackId;
        String source = sourceId == null ? "unknown" : sourceId.id;
        String item = providerItemId == null || providerItemId.isEmpty() ? "-" : providerItemId;
        String digest = canonicalDigest == null || canonicalDigest.isEmpty() ? "-" : canonicalDigest;
        return track + "|" + source + "|" + item + "|" + digest;
    }

    public List<String> providerTransliterationLines() {
        return CatalogCodec.decodeProviderTransliteration(providerTransliteration);
    }

    @Override public boolean equals(Object other) {
        if (!(other instanceof CatalogCandidate)) return false;
        CatalogCandidate o = (CatalogCandidate) other;
        return candidateId.equals(o.candidateId) && trackId.equals(o.trackId) && sourceId == o.sourceId
                && providerItemId.equals(o.providerItemId) && matchMethod == o.matchMethod
                && matchConfidence == o.matchConfidence && durationDeltaMs == o.durationDeltaMs
                && timingLevel == o.timingLevel && complete == o.complete
                && timingHealthy == o.timingHealthy
                && hasProviderTranslation == o.hasProviderTranslation
                && hasProviderTransliteration == o.hasProviderTransliteration
                && hasBackgroundVocals == o.hasBackgroundVocals && hasDuet == o.hasDuet
                && hasCredits == o.hasCredits && canonicalDigest.equals(o.canonicalDigest)
                && normalizedDocument.equals(o.normalizedDocument)
                && providerTransliteration.equals(o.providerTransliteration)
                && java.util.Arrays.equals(rawPayload, o.rawPayload)
                && parserRevision == o.parserRevision && adapterRevision == o.adapterRevision
                && fetchedAtMs == o.fetchedAtMs;
    }

    @Override public int hashCode() {
        return Objects.hash(candidateId, trackId, sourceId, providerItemId, matchMethod,
                matchConfidence, durationDeltaMs, timingLevel, complete, timingHealthy,
                hasProviderTranslation, hasProviderTransliteration, hasBackgroundVocals, hasDuet,
                hasCredits, canonicalDigest, normalizedDocument, providerTransliteration,
                parserRevision, adapterRevision, fetchedAtMs);
    }

    /** Empty transliteration baseline: one empty entry per line count, for legacy imports. */
    public static String emptyTransliteration(int lines) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < Math.max(0, lines); i++) out.add("");
        return CatalogCodec.encodeProviderTransliteration(Collections.unmodifiableList(out));
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }
}
