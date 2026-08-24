package com.eza.spicyex.lyrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

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

        MeaningPreviewRace.Outcome preliminary = race.onGoogleSettled(google);
        assertTrue(preliminary.preliminary);
        assertSame("the first Google settlement is offered as the preliminary", google,
                preliminary.artifact);

        MeaningPreviewRace.Outcome outcome = race.onAiSettled(ai, LayerFailure.NONE);
        assertTrue(outcome.terminal);
        assertSame("AI atomically replaces Google", ai, outcome.artifact);
        assertEquals(LayerFailure.NONE, outcome.failure);
    }

    @Test
    public void aiFirstSuppressesLateGooglePublication() {
        MeaningPreviewRace race = new MeaningPreviewRace();
        MeaningArtifact ai = artifact("ai");

        MeaningPreviewRace.Outcome outcome = race.onAiSettled(ai, LayerFailure.NONE);
        assertTrue(outcome.terminal);
        assertSame(ai, outcome.artifact);
        MeaningPreviewRace.Outcome late = race.onGoogleSettled(artifact("late"));
        assertFalse("AI success settled first: late Google must never be offered", late.terminal);
        assertFalse(late.preliminary);
    }

    @Test
    public void queuedGooglePublicationIsSuppressedWhenAiSettlesBeforeMainThreadRender() {
        MeaningPreviewRace race = new MeaningPreviewRace();
        MeaningArtifact google = artifact("google");
        MeaningArtifact ai = artifact("ai");

        assertSame(google, race.onGoogleSettled(google).artifact);
        assertEquals(true, race.mayPublishPreliminary(google));
        race.onAiSettled(ai, LayerFailure.NONE);
        assertEquals("a queued callback must not regress completed AI", false,
                race.mayPublishPreliminary(google));
    }

    @Test
    public void googleSuccessThenAiFailureKeepsGoogleAndReportsTheFailure() {
        MeaningPreviewRace race = new MeaningPreviewRace();
        MeaningArtifact google = artifact("google");

        assertSame(google, race.onGoogleSettled(google).artifact);

        MeaningPreviewRace.Outcome outcome = race.onAiSettled(null, FAILURE);
        assertTrue(outcome.terminal);
        assertSame("Google remains visible", google, outcome.artifact);
        assertEquals("the AI failure still travels for review/diagnostics", FAILURE,
                outcome.failure);
    }

    @Test
    public void aiFailureThenGoogleSuccessWaitsAndPublishesTheFallback() {
        MeaningPreviewRace race = new MeaningPreviewRace();
        MeaningArtifact google = artifact("google");

        MeaningPreviewRace.Outcome waiting = race.onAiSettled(null, FAILURE);
        assertFalse("AI failure must wait for the in-flight Google child", waiting.terminal);
        assertFalse(waiting.preliminary);

        MeaningPreviewRace.Outcome outcome = race.onGoogleSettled(google);
        assertTrue(outcome.terminal);
        assertSame(google, outcome.artifact);
        assertEquals(FAILURE, outcome.failure);
        assertFalse("Google may settle only once", race.onGoogleSettled(google).terminal);
        assertFalse("AI may settle only once", race.onAiSettled(null, FAILURE).terminal);
    }

    @Test
    public void aiFailureThenGoogleFailureWaitsAndCompletesWithoutOutput() {
        MeaningPreviewRace race = new MeaningPreviewRace();

        assertFalse(race.onAiSettled(null, FAILURE).terminal);

        MeaningPreviewRace.Outcome outcome = race.onGoogleSettled(null);
        assertTrue(outcome.terminal);
        assertNull(outcome.artifact);
        assertEquals(FAILURE, outcome.failure);
    }

    @Test
    public void googleFailureDoesNotBlockOrPrecedeTheAiOutcome() {
        MeaningPreviewRace race = new MeaningPreviewRace();
        MeaningArtifact ai = artifact("ai");

        MeaningPreviewRace.Outcome failedGoogle = race.onGoogleSettled(null);
        assertFalse("a failed Google offers nothing", failedGoogle.preliminary);
        MeaningPreviewRace.Outcome duplicate = race.onGoogleSettled(artifact("retry"));
        assertFalse("a second Google settlement cannot resurrect the preliminary",
                duplicate.preliminary);

        MeaningPreviewRace.Outcome outcome = race.onAiSettled(ai, LayerFailure.NONE);
        assertSame(ai, outcome.artifact);
        assertEquals(LayerFailure.NONE, outcome.failure);
    }

    @Test
    public void bothFailuresSettleOnceWithNothingToPublish() {
        MeaningPreviewRace race = new MeaningPreviewRace();

        assertFalse(race.onGoogleSettled(null).preliminary);

        MeaningPreviewRace.Outcome outcome = race.onAiSettled(null, FAILURE);
        assertTrue(outcome.terminal);
        assertNull("the original/provider baseline stays on screen", outcome.artifact);
        assertEquals(FAILURE, outcome.failure);
    }

    @Test
    public void aMissingAiArtifactWithNoFailureWaitsForGoogleThenCompletesHonestly() {
        MeaningPreviewRace race = new MeaningPreviewRace();

        MeaningPreviewRace.Outcome waiting = race.onAiSettled(null, null);
        assertFalse(waiting.terminal);

        MeaningPreviewRace.Outcome outcome = race.onGoogleSettled(null);
        assertTrue(outcome.terminal);
        assertNull(outcome.artifact);
        assertEquals("nothing-to-do is not a failure", LayerFailure.NONE, outcome.failure);
    }

    @Test
    public void anAbandonedAiSideSilencesEveryLaterGooglePublication() {
        MeaningPreviewRace race = new MeaningPreviewRace();
        race.abandonAi();

        MeaningPreviewRace.Outcome outcome = race.onGoogleSettled(artifact("google"));
        assertFalse("no completion will ever follow, so Google must not publish", outcome.terminal);
        assertFalse(outcome.preliminary);
    }
}
