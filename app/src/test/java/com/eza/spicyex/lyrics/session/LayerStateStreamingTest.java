package com.eza.spicyex.lyrics.session;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;

/** Streamed AI rows replace the preview row by row instead of waiting for the final swap. */
public class LayerStateStreamingTest {
    private static MeaningArtifact google() {
        return new MeaningArtifact("base", "google-cfg",
                new LayerProvenance(LayerAuthority.MACHINE, "google", "google-cfg", 1L),
                Arrays.asList(new MeaningEntry("r0", "G zero", "en"),
                        new MeaningEntry("r1", "G one", "en")), true);
    }

    private static MeaningArtifact ai(boolean partial, MeaningEntry... rows) {
        return new MeaningArtifact("base", "ai-cfg",
                new LayerProvenance(LayerAuthority.AI, "openai", "ai-cfg", 2L),
                Arrays.asList(rows), partial);
    }

    private static String text(LayerState state, String row) {
        return ((MeaningEntry) state.artifact.allEntries().stream()
                .filter(entry -> entry.rowId().equals(row)).findFirst().get()).text;
    }

    @Test
    public void aStreamedAiRowReplacesItsPreviewRowAndKeepsTheRest() {
        LayerState state = LayerState.absent(LayerKind.MEANING)
                .withDelta(google(), LayerStatus.PROCESSING)
                .withDelta(ai(true, new MeaningEntry("r0", "AI zero", "en")),
                        LayerStatus.PROCESSING);
        assertEquals("AI zero", text(state, "r0"));
        assertEquals("G one", text(state, "r1"));
        state = state.withDelta(ai(true, new MeaningEntry("r0", "AI zero", "en"),
                new MeaningEntry("r1", "AI one", "en")), LayerStatus.PROCESSING);
        assertEquals("AI one", text(state, "r1"));
    }

    @Test
    public void aGooglePreviewNeverOverwritesAiRows() {
        LayerState state = LayerState.absent(LayerKind.MEANING)
                .withDelta(ai(false, new MeaningEntry("r0", "AI zero", "en")),
                        LayerStatus.PROCESSING)
                .withDelta(google(), LayerStatus.PROCESSING);
        assertEquals("AI zero", text(state, "r0"));
    }

    @Test
    public void deliveredRowsGrowWhileStreamingAndClearWhenTheRunSettles() {
        LayerState state = LayerState.absent(LayerKind.MEANING)
                .processing(LayerAuthority.AI, "ai-cfg", "", "run")
                .withDelta(google(), LayerStatus.PROCESSING);
        org.junit.Assert.assertTrue(state.aiDeliveredRows.isEmpty());
        state = state.withDelta(ai(true, new MeaningEntry("r0", "AI zero", "en")),
                LayerStatus.PROCESSING);
        assertEquals(java.util.Collections.singleton("r0"), state.aiDeliveredRows);
        state = state.settled(ai(false, new MeaningEntry("r0", "AI zero", "en"),
                new MeaningEntry("r1", "AI one", "en")), LayerFailure.NONE);
        org.junit.Assert.assertTrue(state.aiDeliveredRows.isEmpty());
    }
}
