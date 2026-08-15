package com.eza.spicyex.lyrics.session;

/**
 * One immutable timed span of canonical source text (a syllable in word-synced lyrics).
 * Carries source text and timing only; never generated reading or translation text.
 */
public final class CanonicalSpan {
    public final String spanId;
    public final String text;
    public final long startMs;
    public final long endMs;

    public CanonicalSpan(String spanId, String text, long startMs, long endMs) {
        this.spanId = Digests.nz(spanId);
        this.text = Digests.nz(text);
        this.startMs = startMs;
        this.endMs = endMs;
    }

    String digestPayload() {
        return spanId + Digests.SEP + text + Digests.SEP + startMs + Digests.SEP + endMs;
    }
}
