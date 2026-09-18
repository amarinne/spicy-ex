package com.eza.spicyex.lyrics;

import com.eza.spicyex.lyrics.session.DetectionResult;

/** Synchronous lookup of a session detection result for arbitrary text. */
public interface TextDetectionLookup {
    /** No cached detection; consumers must fall back to declared metadata and script checks. */
    TextDetectionLookup NONE = text -> null;

    /** Terminal result for {@code text}, or null when no cached detection covers it. */
    DetectionResult detectionFor(String text);
}
