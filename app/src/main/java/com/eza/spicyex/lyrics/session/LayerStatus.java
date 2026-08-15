package com.eza.spicyex.lyrics.session;

/** Lifecycle state of one derived layer within a session. */
public enum LayerStatus {
    /** Nothing selected or nothing to do. */
    ABSENT,
    /** A compatible artifact came from cache and is displayed. */
    CACHED,
    /** A run is in flight. */
    PROCESSING,
    /** A fresh run completed and is displayed. */
    READY,
    /** The last run failed; any previously displayed artifact is stated separately. */
    FAILED,
    /** A previously accepted artifact was restored locally at zero cost. */
    RESTORED;

    public boolean hasArtifact() {
        return this == CACHED || this == READY || this == RESTORED;
    }
}
