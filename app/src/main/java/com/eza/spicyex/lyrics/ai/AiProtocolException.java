package com.eza.spicyex.lyrics.ai;

/**
 * A response that does not satisfy the contract.
 *
 * <p>The message is a stable machine token, optionally naming the row that failed — for example
 * {@code delimiter_mismatch:r7#a1b2}. It is safe to store and to log: it identifies which rule and
 * which row, and carries no lyric text.
 */
public class AiProtocolException extends RuntimeException {
    public AiProtocolException(String code) {
        super(code);
    }

    /** {@code code:id} — the row is named so a repair can say what was wrong. */
    public AiProtocolException(String code, String id) {
        super(code + ":" + AiText.nz(id));
    }
}
