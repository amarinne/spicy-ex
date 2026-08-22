package com.eza.spicyex.lyrics.ai;

/**
 * The selected model's token limits, injected into the planner.
 *
 * <p>Injected rather than looked up so chunk membership is a pure function of the document and the
 * limits it was planned against. Offline tests supply fakes; the real values arrive with the
 * explicitly selected model.
 */
public class AiModelLimits {
    public final int inputTokenLimit;
    public final int outputTokenLimit;

    public AiModelLimits(int inputTokenLimit, int outputTokenLimit) {
        this.inputTokenLimit = inputTokenLimit;
        this.outputTokenLimit = outputTokenLimit;
    }
}
