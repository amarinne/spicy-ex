package com.eza.spicyex.lyrics.session;

/** One derived value addressed by a stable canonical row ID. */
public interface LayerEntry {
    String rowId();

    /** Content contribution to the artifact digest. Must be deterministic. */
    String digestPayload();
}
