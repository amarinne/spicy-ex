package com.eza.spicyex.lyrics.session;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Durable detection record for one canonical base.
 *
 * <p>Rows are addressed by canonical row ID and validated against their source text on restore, so
 * a changed lyric line invalidates only its own detection. The record is compact, versioned by its
 * own schema, and carries the detector policy identity that produced it.
 */
public final class DetectionArtifact {
    /** Detection schema identity. A bump invalidates detection records only. */
    public static final int SCHEMA_VERSION = 1;

    /** Identity of the detector policy stack that produced a record. */
    public static final String DETECTOR_POLICY_ID = "context-router-v4+charsoup-core-4.0.0-p80-h95-m20-dochan-yue2zh";

    public final String canonicalDigest;
    public final String detectorPolicyId;
    /** True when some rows are still unclassified; a partial record still applies. */
    public final boolean partial;

    private final Map<String, DetectionResult> rows;

    public DetectionArtifact(String canonicalDigest, String detectorPolicyId,
                             Collection<DetectionResult> rows, boolean partial) {
        this.canonicalDigest = Digests.nz(canonicalDigest);
        this.detectorPolicyId = Digests.nz(detectorPolicyId).isEmpty()
                ? DETECTOR_POLICY_ID : detectorPolicyId;
        LinkedHashMap<String, DetectionResult> indexed = new LinkedHashMap<>();
        if (rows != null) {
            for (DetectionResult row : rows) {
                if (row == null || Digests.nz(row.rowId).isEmpty()) continue;
                indexed.put(row.rowId, row);
            }
        }
        this.rows = Collections.unmodifiableMap(indexed);
        this.partial = partial;
    }

    public static DetectionArtifact empty(String canonicalDigest) {
        return new DetectionArtifact(canonicalDigest, DETECTOR_POLICY_ID,
                Collections.<DetectionResult>emptyList(), true);
    }

    public DetectionResult result(String rowId) {
        return rows.get(Digests.nz(rowId));
    }

    /** Rows the record carries, in canonical insertion order. */
    public List<DetectionResult> allRows() {
        return new ArrayList<>(rows.values());
    }

    public boolean isEmpty() {
        return rows.isEmpty();
    }

    public int size() {
        return rows.size();
    }

    /** This record with {@code delta}'s rows taking precedence, one row at a time. */
    public DetectionArtifact merged(DetectionArtifact delta) {
        if (delta == null || delta.isEmpty()) return this;
        if (!delta.canonicalDigest.isEmpty() && !delta.canonicalDigest.equals(canonicalDigest)) {
            return this;
        }
        LinkedHashMap<String, DetectionResult> next = new LinkedHashMap<>(rows);
        next.putAll(delta.rows);
        return new DetectionArtifact(canonicalDigest, detectorPolicyId,
                new ArrayList<>(next.values()), partial && delta.partial);
    }

    public DetectionArtifact withPartial(boolean nextPartial) {
        return new DetectionArtifact(canonicalDigest, detectorPolicyId, rows.values(), nextPartial);
    }
}
