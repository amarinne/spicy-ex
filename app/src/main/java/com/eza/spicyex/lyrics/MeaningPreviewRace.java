package com.eza.spicyex.lyrics;

import com.eza.spicyex.lyrics.session.LayerFailure;
import com.eza.spicyex.lyrics.session.CanonicalBase;
import com.eza.spicyex.lyrics.session.MeaningArtifact;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Settlement-order rules for the Google preview race, as a plain synchronized object.
 *
 * <p>Two independent children work under one parent run: a Google display job on the lane
 * executor and a raw-lyrics AI job on the AI executor. This object owns which outcome may reach
 * the screen, so the lane stays free of order bookkeeping. The contract, mechanically:
 *
 * <ul>
 *   <li>Google first, then AI — Google publishes once as preliminary, AI replaces it.</li>
 *   <li>AI success first — late Google is ignored and can never regress the display.</li>
 *   <li>AI failure first — completion waits for Google, then publishes its fallback if usable.</li>
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

    private final CanonicalBase base;
    private final Set<String> requiredRows;

    /** True once the AI child settled or failed to launch. */
    private boolean aiSettled;
    /** True once the Google child settled at all — it runs once, so there is no second chance. */
    private boolean googleSettled;
    private boolean googleSucceeded;
    /** True once one child produced the final outcome; no later settlement may replace it. */
    private boolean completionSettled;
    /** Guards the at-most-once preliminary publication. */
    private boolean preliminaryOffered;
    private MeaningArtifact googleArtifact;
    private LayerFailure aiFailure = LayerFailure.NONE;

    MeaningPreviewRace() {
        this(null, Collections.<String>emptySet());
    }

    MeaningPreviewRace(CanonicalBase base, Set<String> requiredRows) {
        this.base = base;
        this.requiredRows = requiredRows == null ? Collections.<String>emptySet()
                : Collections.unmodifiableSet(new LinkedHashSet<>(requiredRows));
    }

    /**
     * Settles the Google child.
     *
     * @return a preliminary Google publication while AI is pending, a terminal fallback after AI
     *         failed, or a no-op when this settlement must not reach the screen
     */
    synchronized Outcome onGoogleSettled(MeaningArtifact settled) {
        if (googleSettled) return Outcome.none();
        googleSettled = true;
        boolean usable = settled != null && !settled.isEmpty()
                && (base == null || MeaningDisplaySelector.isComplete(
                        base, requiredRows, settled));
        googleSucceeded = usable;
        googleArtifact = usable ? settled : null;
        if (completionSettled) return Outcome.none();
        if (aiSettled) {
            completionSettled = true;
            return Outcome.complete(googleArtifact, aiFailure);
        }
        if (!usable || preliminaryOffered) return Outcome.none();
        preliminaryOffered = true;
        return Outcome.preliminary(googleArtifact);
    }

    /**
     * Rechecks the preliminary at publication time. The main-thread callback may be queued while
     * AI settles on another executor; in that ordering, Google must not render after completed AI.
     */
    synchronized boolean mayPublishPreliminary(MeaningArtifact offered) {
        return !completionSettled && preliminaryOffered && googleArtifact == offered;
    }

    /**
     * Marks the AI side dead before it could start (executor rejection). Any later Google
     * settlement is suppressed, because nothing will ever complete the run after it.
     */
    synchronized void abandonAi() {
        aiSettled = true;
        completionSettled = true;
    }

    /**
     * Settles the AI child and decides the single final publication.
     *
     * @param artifact what the raw-AI request produced, or null on failure/nothing-to-do
     * @param failure  the mapped AI failure, {@link LayerFailure#NONE} when there is none
     */
    synchronized Outcome onAiSettled(MeaningArtifact artifact, LayerFailure failure) {
        if (aiSettled || completionSettled) return Outcome.none();
        aiSettled = true;
        if (artifact != null && !artifact.isEmpty()) {
            MeaningArtifact selected = base == null ? artifact
                    : MeaningDisplaySelector.select(base, requiredRows, artifact, googleArtifact);
            completionSettled = true;
            return Outcome.complete(selected, LayerFailure.NONE);
        }
        aiFailure = failure == null ? LayerFailure.NONE : failure;
        if (!googleSettled) return Outcome.none();
        completionSettled = true;
        // A Google artifact that landed first stays visible; the AI failure still reports so the
        // review panel can explain why the screen shows Google while the sparkle reads red.
        if (googleSucceeded && googleArtifact != null) {
            return Outcome.complete(googleArtifact, aiFailure);
        }
        return Outcome.complete(null, aiFailure);
    }

    /** The one final decision: what to fold into the session, and which failure to report. */
    static final class Outcome {
        final MeaningArtifact artifact;
        final LayerFailure failure;
        final boolean terminal;
        final boolean preliminary;

        private Outcome(MeaningArtifact artifact, LayerFailure failure, boolean terminal,
                        boolean preliminary) {
            this.artifact = artifact;
            this.failure = failure == null ? LayerFailure.NONE : failure;
            this.terminal = terminal;
            this.preliminary = preliminary;
        }

        static Outcome none() {
            return new Outcome(null, LayerFailure.NONE, false, false);
        }

        static Outcome preliminary(MeaningArtifact artifact) {
            return new Outcome(artifact, LayerFailure.NONE, false, true);
        }

        static Outcome complete(MeaningArtifact artifact, LayerFailure failure) {
            return new Outcome(artifact, failure, true, false);
        }
    }
}
