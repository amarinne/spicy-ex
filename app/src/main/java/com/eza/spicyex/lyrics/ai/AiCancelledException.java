package com.eza.spicyex.lyrics.ai;

/**
 * The run was cancelled. Carries the reason token so a caller can tell a track change from a
 * credential change without parsing a message.
 */
public class AiCancelledException extends RuntimeException {
    public final String reason;

    public AiCancelledException(String reason) {
        super(AiText.nz(reason));
        this.reason = AiText.nz(reason);
    }
}
