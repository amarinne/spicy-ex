package com.eza.spicyex.lyrics.ai;

import com.eza.spicyex.lyrics.session.LayerKind;

/**
 * Binds one run's identity to {@link AiRequestLiveState}.
 *
 * <p>The identity triple — layer, canonical digest, run tag — is what makes a stale run unable to
 * overwrite the current one, and every lane that starts a run has to repeat it on each callback.
 * With one callback that was a lambda per lane; with two it became the same four lines written
 * three times, which is how one of them ends up recording against the wrong layer.
 */
public final class AiLiveMonitor implements AiRunMonitor {
    private final LayerKind layer;
    private final String canonicalDigest;
    private final String runId;

    public AiLiveMonitor(LayerKind layer, String canonicalDigest, String runId) {
        this.layer = layer;
        this.canonicalDigest = canonicalDigest;
        this.runId = runId;
    }

    @Override public void onAttempt(String chunkId, int attempt, String wirePayload) {
        AiRequestLiveState.attempt(layer, canonicalDigest, runId, chunkId, attempt, wirePayload);
    }

    @Override public void onReasoning(String chunkId, int attempt, String reasoning) {
        AiRequestLiveState.reasoning(layer, canonicalDigest, runId, chunkId, attempt, reasoning);
    }
}
