package com.eza.spicyex.lyrics.ai;

/**
 * The document, a row, or a chunk cannot be made to fit the transport and model bounds.
 *
 * <p>Thrown before anything is dispatched. Nothing is billed for an oversized document, so this is
 * a planning outcome rather than a failure of a call.
 */
public class AiOversizedException extends RuntimeException {
    public AiOversizedException(String detail) {
        super("oversized:" + AiText.nz(detail));
    }
}
