package com.eza.spicyex.lyrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import com.eza.spicyex.lyrics.session.LayerAuthority;
import com.eza.spicyex.lyrics.session.LayerFailure;
import com.eza.spicyex.lyrics.session.LayerProvenance;
import com.eza.spicyex.lyrics.session.MeaningArtifact;
import com.eza.spicyex.lyrics.session.MeaningEntry;

import org.junit.Test;

import java.util.Collections;

/**
 * The Google preview race table from the handover, settled one child at a time. Every row of the
 * display contract maps to one test here; if the state machine drifts from the table, a row fails.
 */
public final class MeaningPreviewRaceTest {

    private static MeaningArtifact artifact(String marker) {
        return new MeaningArtifact("digest", "config",
                new LayerProvenance(LayerAuthority.MACHINE, marker, "contract", 0L),
                Collections.singletonList(new MeaningEntry("row-" + marker, marker + " text", "en")),
                false);
    }

    private static final LayerFailure FAILURE =
            new LayerFailure(LayerFailure.Reason.SERVER_ERROR, "http_500", 500);

    @Test
    public void googleSuccessThenAiSuccessPublishesPreliminaryThenReplaces() {
        MeaningPreviewRace race = new MeaningPreviewRace();
        MeaningArtifact google = artifact("google");
        MeaningArtifact ai = artifact("ai");

        assertSame("the first Google settlement is offered as the preliminary", google,
                race.preliminaryFor(google));

        MeaningPreviewRace.Outcome outcome = race.onAiSettled(ai, LayerFailure.NONE);
        assertSame("AI atomically replaces Google", ai, outcome.artifact);
        assertEquals(LayerFailure.NONE, outcome.failure);
    }

    @Test
    public void aiFirstSuppressesLateGooglePublication() {
        MeaningPreviewRace race = new MeaningPreviewRace();
        MeaningArtifact ai = artifact("ai");

        MeaningPreviewRace.Outcome outcome = race.onAiSettled(ai, LayerFailure.NONE);
        assertSame(ai, outcome.artifact);
        assertNull("AI settled first: late Google must never be offered", race.preliminaryFor(artifact("late")));
    }

    @Test
    public void queuedGooglePublicationIsSuppressedWhenAiSettlesBeforeMainThreadRender() {
        MeaningPreviewRace race = new MeaningPreviewRace();
        MeaningArtifact google = artifact("google");
        MeaningArtifact ai = artifact("ai");

        assertSame(google, race.preliminaryFor(google));
        assertEquals(true, race.mayPublishPreliminary(google));
        race.onAiSettled(ai, LayerFailure.NONE);
        assertEquals("a queued callback must not regress completed AI", false,
                race.mayPublishPreliminary(google));
    }

    @Test
    public void googleSuccessThenAiFailureKeepsGoogleAndReportsTheFailure() {
        MeaningPreviewRace race = new MeaningPreviewRace();
        MeaningArtifact google = artifact("google");

        assertSame(google, race.preliminaryFor(google));

        MeaningPreviewRace.Outcome outcome = race.onAiSettled(null, FAILURE);
        assertSame("Google remains visible", google, outcome.artifact);
        assertEquals("the AI failure still travels for review/diagnostics", FAILURE,
                outcome.failure);
    }

    @Test
    public void googleFailureDoesNotBlockOrPrecedeTheAiOutcome() {
        MeaningPreviewRace race = new MeaningPreviewRace();
        MeaningArtifact ai = artifact("ai");

        assertNull("a failed Google offers nothing", race.preliminaryFor(null));
        assertNull("a second Google settlement cannot resurrect the preliminary",
                race.preliminaryFor(artifact("retry")));

        MeaningPreviewRace.Outcome outcome = race.onAiSettled(ai, LayerFailure.NONE);
        assertSame(ai, outcome.artifact);
        assertEquals(LayerFailure.NONE, outcome.failure);
    }

    @Test
    public void bothFailuresSettleOnceWithNothingToPublish() {
        MeaningPreviewRace race = new MeaningPreviewRace();

        assertNull(race.preliminaryFor(null));

        MeaningPreviewRace.Outcome outcome = race.onAiSettled(null, FAILURE);
        assertNull("the original/provider baseline stays on screen", outcome.artifact);
        assertEquals(FAILURE, outcome.failure);
    }

    @Test
    public void aMissingAiArtifactWithNoFailureStillCompletesHonestly() {
        MeaningPreviewRace race = new MeaningPreviewRace();

        MeaningPreviewRace.Outcome outcome = race.onAiSettled(null, null);
        assertNull(outcome.artifact);
        assertEquals("nothing-to-do is not a failure", LayerFailure.NONE, outcome.failure);
    }

    @Test
    public void anAbandonedAiSideSilencesEveryLaterGooglePublication() {
        MeaningPreviewRace race = new MeaningPreviewRace();
        race.abandonAi();

        assertNull("no completion will ever follow, so Google must not publish",
                race.preliminaryFor(artifact("google")));
    }
}
