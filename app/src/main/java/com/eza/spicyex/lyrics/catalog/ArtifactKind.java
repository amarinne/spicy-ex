package com.eza.spicyex.lyrics.catalog;

/** Derived enrichment the catalog keeps per lyric body. */
public enum ArtifactKind {
    SOUND,
    MEANING,
    /** Per-row language detection for one canonical body. */
    DETECTION,
    /** Detection of provider-translation text, keyed by the text itself. */
    DETECTION_TEXT
}
