package com.eza.spicyex.lyrics.session;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Derived translation artifact for one canonical base and one Meaning configuration. */
public final class MeaningArtifact extends DerivedLayerArtifact {

    public final boolean refinedFromGoogle;
    private final List<MeaningEntry> googleBaselineEntries;

    public MeaningArtifact(String canonicalDigest, String configId, LayerProvenance provenance,
                           Collection<MeaningEntry> entries, boolean partial) {
        this(canonicalDigest, configId, provenance, entries, partial, false, null);
    }

    public MeaningArtifact(String canonicalDigest, String configId, LayerProvenance provenance,
                           Collection<MeaningEntry> entries, boolean partial,
                           boolean refinedFromGoogle,
                           Collection<MeaningEntry> googleBaselineEntries) {
        super(LayerKind.MEANING, canonicalDigest, configId, provenance, entries, partial);
        this.refinedFromGoogle = refinedFromGoogle;
        this.googleBaselineEntries = new ArrayList<>();
        if (googleBaselineEntries != null) this.googleBaselineEntries.addAll(googleBaselineEntries);
    }

    public MeaningEntry meaning(String rowId) {
        LayerEntry entry = entry(rowId);
        return entry instanceof MeaningEntry ? (MeaningEntry) entry : null;
    }

    public MeaningArtifact merged(MeaningArtifact delta) {
        return (MeaningArtifact) mergedWith(delta);
    }

    public MeaningArtifact withGoogleBaseline(MeaningArtifact baseline) {
        return withGoogleBaseline(baseline, true);
    }

    public MeaningArtifact withGoogleBaseline(MeaningArtifact baseline,
                                              boolean refinedFromGoogle) {
        if (baseline == null || baseline.isEmpty()) return this;
        List<MeaningEntry> typed = new ArrayList<>();
        for (LayerEntry entry : allEntries()) {
            if (entry instanceof MeaningEntry) typed.add((MeaningEntry) entry);
        }
        List<MeaningEntry> google = new ArrayList<>();
        for (LayerEntry entry : baseline.allEntries()) {
            if (entry instanceof MeaningEntry) google.add((MeaningEntry) entry);
        }
        return new MeaningArtifact(canonicalDigest, configId, provenance, typed, partial,
                refinedFromGoogle, google);
    }

    public MeaningArtifact googleBaseline() {
        if (googleBaselineEntries.isEmpty()) return null;
        return new MeaningArtifact(canonicalDigest, configId,
                new LayerProvenance(LayerAuthority.MACHINE, "google_unofficial",
                        configId, System.currentTimeMillis()),
                googleBaselineEntries, false);
    }

    @Override
    DerivedLayerArtifact recreate(Collection<? extends LayerEntry> nextEntries,
                                  LayerProvenance nextProvenance, boolean nextPartial) {
        List<MeaningEntry> typed = new ArrayList<>();
        for (LayerEntry entry : nextEntries) {
            if (entry instanceof MeaningEntry) typed.add((MeaningEntry) entry);
        }
        return new MeaningArtifact(canonicalDigest, configId, nextProvenance, typed, nextPartial,
                refinedFromGoogle, googleBaselineEntries);
    }
}
