package com.eza.spicyex.lyrics.session;

/** How much a detection row knows, and whether the result is final. */
public enum DetectionStatus {
    /** A language was resolved, either by the detector or by an unambiguous script. */
    DETECTED,
    /** The script is known but no model maps it to a language (for example Indic). */
    SCRIPT_ONLY,
    /**
     * A terminal negative: the detector ran and produced no language (punctuation-only rows,
     * ambiguous short text). Persisted so the row is never detected again.
     */
    UNKNOWN,
    /** A transient failure (model load error, interruption). Retried on the next load. */
    ERROR;

    /** True when a stored row with this status is final and may be reused without detection. */
    public boolean isTerminal() {
        return this != ERROR;
    }

    public static DetectionStatus fromName(String name) {
        if (name == null) return UNKNOWN;
        for (DetectionStatus status : values()) {
            if (status.name().equalsIgnoreCase(name)) return status;
        }
        return UNKNOWN;
    }
}
