package com.eza.spicyex.lyrics.ai;

/**
 * What one provider call produced.
 *
 * <p>A successful result carries the model's <em>raw text</em>, not parsed items. That is the whole
 * point of the seam: fence tolerance and strict parsing happen once, in
 * {@link AiResponseReader}, so every adapter gets the same treatment and no new adapter can arrive
 * with its own slightly different idea of what a valid response looks like.
 */
public final class AiProviderResult {
    public final boolean ok;
    /** Model output, exactly as received. Null on failure. */
    public final String rawText;
    public final AiUsage usage;
    public final AiFinishReason finish;
    /** Response size, for logging and the byte ceiling. */
    public final long rawBytes;
    public final AiProviderFailure failure;

    private AiProviderResult(boolean ok, String rawText, AiUsage usage, AiFinishReason finish,
                             long rawBytes, AiProviderFailure failure) {
        this.ok = ok;
        this.rawText = rawText;
        this.usage = usage == null ? AiUsage.UNREPORTED : usage;
        this.finish = finish;
        this.rawBytes = rawBytes;
        this.failure = failure;
    }

    public static AiProviderResult ok(String rawText, AiUsage usage, AiFinishReason finish,
                                      long rawBytes) {
        return new AiProviderResult(true, AiText.nz(rawText), usage,
                finish == null ? AiFinishReason.OTHER : finish, rawBytes, null);
    }

    public static AiProviderResult failed(AiProviderFailure failure) {
        return new AiProviderResult(false, null, AiUsage.UNREPORTED, null, 0L, failure);
    }
}
