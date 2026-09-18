package com.eza.spicyex.lyrics.session;

/** Provenance of the language label, independent of translation target. */
public enum DetectionEvidence {
    NONE, SCRIPT, MODEL, CONTEXT;

    public static DetectionEvidence fromName(String name) {
        for (DetectionEvidence value : values()) if (value.name().equals(name)) return value;
        return NONE;
    }
}
