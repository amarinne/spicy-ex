package com.eza.spicyex.lyrics.session;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Derived translation artifact for one canonical base and one Meaning configuration. */
public final class MeaningArtifact extends DerivedLayerArtifact {

    public MeaningArtifact(String canonicalDigest, String configId, LayerProvenance provenance,
                           Collection<MeaningEntry> entries, boolean partial) {
        super(LayerKind.MEANING, canonicalDigest, configId, provenance, entries, partial);
    }

    public MeaningEntry meaning(String rowId) {
        LayerEntry entry = entry(rowId);
        return entry instanceof MeaningEntry ? (MeaningEntry) entry : null;
    }

    public MeaningArtifact merged(MeaningArtifact delta) {
        return (MeaningArtifact) mergedWith(delta);
    }

    @Override
    DerivedLayerArtifact recreate(Collection<? extends LayerEntry> nextEntries,
                                  LayerProvenance nextProvenance, boolean nextPartial) {
        List<MeaningEntry> typed = new ArrayList<>();
        for (LayerEntry entry : nextEntries) {
            if (entry instanceof MeaningEntry) typed.add((MeaningEntry) entry);
        }
        return new MeaningArtifact(canonicalDigest, configId, nextProvenance, typed, nextPartial);
    }
}
