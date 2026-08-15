package com.eza.spicyex.lyrics.session;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * An immutable set of derived values for one layer, bound to one canonical digest and one layer
 * configuration.
 *
 * <p>Entries are addressed by stable canonical row ID. An artifact whose rows are not all present
 * in a base is not applicable to that base — count-only or positional fallback is never valid.
 * A {@link #partial} artifact is a bounded delta: valid on its own, but not proof the layer is done.
 */
public class DerivedLayerArtifact {
    public final LayerKind kind;
    public final String canonicalDigest;
    public final String configId;
    public final LayerProvenance provenance;
    public final boolean partial;
    public final String artifactDigest;

    private final Map<String, LayerEntry> entries;

    public DerivedLayerArtifact(LayerKind kind, String canonicalDigest, String configId,
                                LayerProvenance provenance, Collection<? extends LayerEntry> entries,
                                boolean partial) {
        this.kind = kind;
        this.canonicalDigest = Digests.nz(canonicalDigest);
        this.configId = Digests.nz(configId);
        this.provenance = provenance;
        this.partial = partial;
        Map<String, LayerEntry> byRow = new LinkedHashMap<>();
        if (entries != null) {
            for (LayerEntry entry : entries) {
                if (entry == null || Digests.nz(entry.rowId()).isEmpty()) continue;
                byRow.put(entry.rowId(), entry);
            }
        }
        this.entries = Collections.unmodifiableMap(byRow);
        this.artifactDigest = computeDigest();
    }

    public Set<String> rowIds() {
        return entries.keySet();
    }

    public int size() {
        return entries.size();
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public LayerEntry entry(String rowId) {
        return entries.get(rowId);
    }

    public Collection<LayerEntry> allEntries() {
        return entries.values();
    }

    /** True when every entry addresses a row that exists in {@code base} and digests agree. */
    public boolean appliesTo(CanonicalBase base) {
        if (base == null || !base.digest.equals(canonicalDigest)) return false;
        for (String rowId : entries.keySet()) {
            if (!base.hasRow(rowId)) return false;
        }
        return true;
    }

    /** True when this artifact was produced for the same base and layer configuration. */
    public boolean isCompatibleWith(String otherCanonicalDigest, String otherConfigId) {
        return canonicalDigest.equals(Digests.nz(otherCanonicalDigest))
                && configId.equals(Digests.nz(otherConfigId));
    }

    /**
     * Folds a bounded delta over this artifact. The delta must share canonical digest, config, and
     * layer kind; otherwise this artifact is returned unchanged.
     */
    public DerivedLayerArtifact mergedWith(DerivedLayerArtifact delta) {
        if (delta == null || delta.kind != kind
                || !delta.canonicalDigest.equals(canonicalDigest)
                || !delta.configId.equals(configId)) {
            return this;
        }
        Map<String, LayerEntry> merged = new LinkedHashMap<>(entries);
        merged.putAll(delta.entries);
        return recreate(merged.values(), delta.provenance == null ? provenance : delta.provenance,
                partial && delta.partial);
    }

    DerivedLayerArtifact recreate(Collection<? extends LayerEntry> nextEntries,
                                  LayerProvenance nextProvenance, boolean nextPartial) {
        return new DerivedLayerArtifact(kind, canonicalDigest, configId, nextProvenance, nextEntries, nextPartial);
    }

    private String computeDigest() {
        StringBuilder payload = new StringBuilder(128);
        payload.append(kind.name()).append(Digests.SEP).append(canonicalDigest)
                .append(Digests.SEP).append(configId).append(Digests.SEP)
                .append(provenance == null ? "" : provenance.digestPayload())
                .append(Digests.SEP).append(partial ? '1' : '0');
        for (LayerEntry entry : entries.values()) {
            payload.append(Digests.SEP).append(entry.digestPayload());
        }
        return Digests.sha256(payload.toString());
    }
}
