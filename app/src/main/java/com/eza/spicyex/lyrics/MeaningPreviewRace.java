package com.eza.spicyex.lyrics;

import com.eza.spicyex.lyrics.session.LayerFailure;
import com.eza.spicyex.lyrics.session.MeaningArtifact;

/**
 * Settlement-order rules for the Google preview race, as a plain synchronized object.
 *
 * <p>Two independent children work under one parent run: a Google display job on the lane
 * executor and a raw-lyrics AI job on the AI executor. This object owns which outcome may reach
 * the screen, so the lane stays free of order bookkeeping. The contract, mechanically:
 *
 * <ul>
 *   <li>Google first, then AI — Google publishes once as preliminary, AI replaces it.</li>
 *   <li>AI first — late Google is ignored and can never regress the display.</li>
 *   <li>Google success then AI failure — Google remains visible; the failure still travels with
 *       the completion for review/diagnostics.</li>
 *   <li>Google failure never blocks AI: no preliminary is offered and the AI outcome decides.</li>
 *   <li>Both fail — nothing replaces the baseline; the completion reports the AI failure.</li>
 * </ul>
 *
 * <p>Retirement (track/config changes) is deliberately not modeled here. Late publications are
 * rejected by the run guard at post time; cancellation of in-flight work belongs to the lane and
 * its provider tag.
 */
final class MeaningPreviewRace {

    /** True once the AI child settled or failed to launch — Google may never publish after it. */
    private boolean aiSettled;
    /** True once the Google child settled at all — it runs once, so there is no second chance. */
    private boolean googleSettled;
    private boolean googleSucceeded;
    /** Guards the at-most-once preliminary publication. */
    private boolean preliminaryOffered;
    private MeaningArtifact googleArtifact;

    /**
     * Settles the Google child.
     *
     * @return the artifact to publish as the preliminary Google translation now, or null when
     *         Google failed, already settled, already published, or the AI child has settled
     *         (or was abandoned)
     */
    synchronized MeaningArtifact preliminaryFor(MeaningArtifact settled) {
        if (aiSettled || googleSettled) return null;
        googleSettled = true;
        boolean usable = settled != null && !settled.isEmpty();
        googleSucceeded = usable;
        googleArtifact = usable ? settled : null;
        if (!usable || preliminaryOffered) return null;
        preliminaryOffered = true;
        return googleArtifact;
    }

    /**
     * Rechecks the preliminary at publication time. The main-thread callback may be queued while
     * AI settles on another executor; in that ordering, Google must not render after completed AI.
     */
    synchronized boolean mayPublishPreliminary(MeaningArtifact offered) {
        return !aiSettled && preliminaryOffered && googleArtifact == offered;
    }

    /**
     * Marks the AI side dead before it could start (executor rejection). Any later Google
     * settlement is suppressed, because nothing will ever complete the run after it.
     */
    synchronized void abandonAi() {
        aiSettled = true;
    }

    /**
     * Settles the AI child and decides the single final publication.
     *
     * @param artifact what the raw-AI request produced, or null on failure/nothing-to-do
     * @param failure  the mapped AI failure, {@link LayerFailure#NONE} when there is none
     */
    synchronized Outcome onAiSettled(MeaningArtifact artifact, LayerFailure failure) {
        aiSettled = true;
        if (artifact != null && !artifact.isEmpty()) {
            return Outcome.replaceWith(artifact);
        }
        // A Google artifact that landed first stays visible; the AI failure still reports so the
        // review panel can explain why the screen shows Google while the sparkle reads red.
        if (googleSucceeded && googleArtifact != null) {
            return Outcome.replaceWith(googleArtifact).withFailure(failure);
        }
        return Outcome.replaceWith(null).withFailure(failure);
    }

    /** The one final decision: what to fold into the session, and which failure to report. */
    static final class Outcome {
        final MeaningArtifact artifact;
        final LayerFailure failure;

        private Outcome(MeaningArtifact artifact, LayerFailure failure) {
            this.artifact = artifact;
            this.failure = failure == null ? LayerFailure.NONE : failure;
        }

        static Outcome replaceWith(MeaningArtifact artifact) {
            return new Outcome(artifact, LayerFailure.NONE);
        }

        Outcome withFailure(LayerFailure next) {
            return new Outcome(artifact, next);
        }
    }
}
