package com.eza.spicyex.lyrics.ai;

/**
 * Why the model stopped generating. A closed mapping, and the authority on refusals.
 *
 * <p>Only {@link #STOP} reaches validation. This is deliberately not inferred from the text: a
 * lyric can open with "I'm sorry", so any heuristic over the returned rows would classify real
 * songs as refusals.
 */
public enum AiFinishReason {
    STOP,
    /** Output hit its cap mid-document. Terminal for the run; retrying blind would re-bill it. */
    LENGTH,
    SAFETY,
    OTHER
}
