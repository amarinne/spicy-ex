package com.eza.spicyex.lyrics.ai;

/** Pure routing policy for a long-press on one AI-capable lyric layer control. */
public final class AiLayerPanelPolicy {
    public enum Destination { RUNNING_STATUS, FAILURE, REVIEW, COMPOSER }

    private AiLayerPanelPolicy() {
    }

    public static Destination destination(boolean running, String failureToken,
                                          boolean hasDisplayedAiOutput) {
        if (running) return Destination.RUNNING_STATUS;
        if (failureToken != null && !failureToken.isEmpty()) return Destination.FAILURE;
        return hasDisplayedAiOutput ? Destination.REVIEW : Destination.COMPOSER;
    }
}
