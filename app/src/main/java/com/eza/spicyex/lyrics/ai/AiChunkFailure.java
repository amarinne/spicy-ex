package com.eza.spicyex.lyrics.ai;

/** A chunk's terminal failure, as stored in its record. Identity-safe: no lyric text, no body. */
public final class AiChunkFailure {
    public final AiFailureReason reason;
    public final int status;
    /** Machine token, often naming the row that failed. Empty when there is nothing to name. */
    public final String detail;

    public AiChunkFailure(AiFailureReason reason, int status, String detail) {
        this.reason = reason;
        this.status = status;
        this.detail = AiText.nz(detail);
    }

    public static AiChunkFailure of(AiFailureReason reason) {
        return new AiChunkFailure(reason, 0, "");
    }
}
