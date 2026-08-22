package com.eza.spicyex.lyrics.ai;

/** UI-local bridge between an accepted AI action and the next session publication. */
public final class AiRequestFeedbackState {
    public enum Outcome {
        NONE,
        COMPLETED,
        FAILED,
        NO_OUTPUT
    }

    private boolean optimisticPending;
    private boolean awaitingSettlement;

    public void started() {
        optimisticPending = true;
        awaitingSettlement = true;
    }

    public boolean isPending(boolean documentPending) {
        return optimisticPending || documentPending;
    }

    public Outcome observe(boolean documentPending, boolean hasAiOutput, String failureToken) {
        if (!awaitingSettlement) return Outcome.NONE;
        if (documentPending) {
            optimisticPending = false;
            return Outcome.NONE;
        }
        optimisticPending = false;
        awaitingSettlement = false;
        if (failureToken != null && !failureToken.trim().isEmpty()) return Outcome.FAILED;
        return hasAiOutput ? Outcome.COMPLETED : Outcome.NO_OUTPUT;
    }
}
