package com.eza.spicyex.lyrics.session;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Derived reading artifact for one canonical base and one Sound configuration. */
public final class SoundArtifact extends DerivedLayerArtifact {

    public SoundArtifact(String canonicalDigest, String configId, LayerProvenance provenance,
                         Collection<SoundEntry> entries, boolean partial) {
        super(LayerKind.SOUND, canonicalDigest, configId, provenance, entries, partial);
    }

    public SoundEntry sound(String rowId) {
        LayerEntry entry = entry(rowId);
        return entry instanceof SoundEntry ? (SoundEntry) entry : null;
    }

    public SoundArtifact merged(SoundArtifact delta) {
        return (SoundArtifact) mergedWith(delta);
    }

    @Override
    DerivedLayerArtifact recreate(Collection<? extends LayerEntry> nextEntries,
                                  LayerProvenance nextProvenance, boolean nextPartial) {
        List<SoundEntry> typed = new ArrayList<>();
        for (LayerEntry entry : nextEntries) {
            if (entry instanceof SoundEntry) typed.add((SoundEntry) entry);
        }
        return new SoundArtifact(canonicalDigest, configId, nextProvenance, typed, nextPartial);
    }
}
