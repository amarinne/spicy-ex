package com.eza.spicyex.lyrics.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.eza.spicyex.lyrics.session.LayerKind;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The planner decides how many times a document is paid for and where the boundaries fall. Both
 * have to be a pure function of the input: a boundary that moved between two runs would make a
 * partial result unresumable and a repeat request billable.
 */
public class AiChunkPlannerTest {

    private static final AiModelLimits MODEL = new AiModelLimits(32_768, 8_192);

    private static AiLine ordinary(String id, String text) {
        return new AiLine(id, AiLineClass.ORDINARY, AiSendDisposition.SENT, text, null, false,
                null, null);
    }

    private static AiChunkPlanner.Input input(List<AiLine> rows, String target) {
        AiChunkPlanner.Input input = new AiChunkPlanner.Input();
        input.rows = rows;
        input.target = target;
        input.model = MODEL;
        return input;
    }

    // --- request bytes ------------------------------------------------------

    @Test
    public void theRequestIsCompactAndInTheContractsKeyOrder() {
        AiChunkPlan plan = AiChunkPlanner.plan(
                input(Collections.singletonList(ordinary("S0", "hola")), "en"));
        assertEquals("{\"context\":{\"title\":null,\"artists\":[],\"album\":null},"
                        + "\"target\":\"en\",\"items\":"
                        + "[{\"id\":\"S0\",\"c\":\"ordinary\",\"v\":null,\"s\":\"hola\"}]}",
                plan.chunks.get(0).requestJson);
    }

    @Test
    public void anAbsentBaselineOmitsItsKeyRatherThanSendingItNull() {
        AiChunkPlan plan = AiChunkPlanner.plan(
                input(Collections.singletonList(ordinary("S0", "hola")), "en"));
        assertFalse(plan.chunks.get(0).requestJson.contains("\"p\""));
    }

    @Test
    public void metadataAndVoiceTravelWithTheRequest() {
        AiLine row = new AiLine("S0", AiLineClass.ORDINARY, AiSendDisposition.SENT, "hola",
                AiVoiceHint.BACKGROUND, false, null, null);
        AiChunkPlanner.Input input = input(Collections.singletonList(row), "en");
        input.context = new AiLyricContext("Song", Arrays.asList("A", "B"), "Album");
        String json = AiChunkPlanner.plan(input).chunks.get(0).requestJson;
        assertEquals("{\"context\":{\"title\":\"Song\",\"artists\":[\"A\",\"B\"],"
                        + "\"album\":\"Album\"},\"target\":\"en\",\"items\":"
                        + "[{\"id\":\"S0\",\"c\":\"ordinary\",\"v\":\"background\",\"s\":\"hola\"}]}",
                json);
    }

    @Test
    public void steeringIsNormalizedIntoTheRequestAndCountedInItsSize() {
        AiChunkPlanner.Input plain = input(Collections.singletonList(ordinary("S0", "hola")), "en");
        AiPlannedChunk bare = AiChunkPlanner.plan(plain).chunks.get(0);

        AiChunkPlanner.Input steered =
                input(Collections.singletonList(ordinary("S0", "hola")), "en");
        steered.instructions = "  Preserve names.  ";
        AiPlannedChunk withSteering = AiChunkPlanner.plan(steered).chunks.get(0);

        assertTrue(withSteering.requestJson.contains("\"instructions\":\"Preserve names.\""));
        assertTrue(withSteering.estimatedInputTokens > bare.estimatedInputTokens);
        assertTrue(AiText.utf8Bytes(withSteering.requestJson)
                > AiText.utf8Bytes(bare.requestJson));
    }

    // --- boundaries ---------------------------------------------------------

    @Test
    public void oneCallCarriesTheWholeDocumentUntilItCannot() {
        List<AiLine> rows = new ArrayList<>();
        for (int i = 0; i < 8; i++) rows.add(ordinary("S" + i, "line " + i));
        assertEquals(1, AiChunkPlanner.plan(input(rows, "en")).chunks.size());
    }

    @Test
    public void pastTheSingleCallBoundTheDocumentChunksDeterministically() {
        List<AiLine> rows = new ArrayList<>();
        for (int i = 0; i < 129; i++) {
            rows.add(new AiLine("S" + i, AiLineClass.ORDINARY, AiSendDisposition.SENT,
                    "\u6e90" + i, i % 2 == 1 ? AiVoiceHint.ALTERNATE : AiVoiceHint.PRIMARY,
                    false, null, null));
        }
        AiChunkPlanner.Input first = input(rows, "en");
        first.context = new AiLyricContext("Song", Collections.singletonList("Artist"), "Album");
        AiChunkPlanner.Input second = input(new ArrayList<>(rows), "en");
        second.context = new AiLyricContext("Song", Collections.singletonList("Artist"), "Album");

        AiChunkPlan left = AiChunkPlanner.plan(first);
        AiChunkPlan right = AiChunkPlanner.plan(second);

        assertEquals(Arrays.asList("C0", "C1", "C2"), ids(left));
        assertEquals(Arrays.asList(64, 64, 1), sizes(left));
        assertEquals(ids(left), ids(right));
        assertEquals(sizes(left), sizes(right));
        for (int i = 0; i < left.chunks.size(); i++) {
            assertEquals("chunk " + i + " must serialize identically on a replan",
                    left.chunks.get(i).requestJson, right.chunks.get(i).requestJson);
        }
        assertEquals(129, left.enumerableRows);
    }

    @Test
    public void structuralRowsCountTowardsTheDocumentButNeverTowardsAChunk() {
        List<AiLine> rows = new ArrayList<>();
        rows.add(AiLine.of("S0", "[Chorus]", null, false));
        rows.add(ordinary("S1", "hola"));
        AiChunkPlan plan = AiChunkPlanner.plan(input(rows, "en"));
        assertEquals(2, plan.enumerableRows);
        assertEquals(1, plan.chunks.get(0).items.size());
        assertEquals("S1", plan.chunks.get(0).items.get(0).id);
    }

    @Test
    public void aDocumentWithNothingToSendCostsNoCall() {
        List<AiLine> rows = new ArrayList<>();
        rows.add(AiLine.of("S0", "[Chorus]", null, false));
        rows.add(AiLine.of("S1", "\u266a", null, false));
        AiChunkPlan plan = AiChunkPlanner.plan(input(rows, "en"));
        assertTrue(plan.isEmpty());
        assertEquals(2, plan.enumerableRows);
    }

    @Test
    public void estimatedOutputIsHalfTheSourceBytesRoundedUp() {
        AiPlannedChunk chunk = AiChunkPlanner.plan(
                input(Collections.singletonList(ordinary("S0", "hello")), "en")).chunks.get(0);
        assertEquals(5, chunk.sourceUtf8Bytes);
        assertEquals(3, chunk.estimatedOutputTokens);
    }

    // --- refusals -----------------------------------------------------------

    @Test
    public void aDocumentPastItsRowOrByteBoundIsRefusedBeforeAnythingIsSpent() {
        List<AiLine> tooManyRows = new ArrayList<>();
        for (int i = 0; i < 513; i++) tooManyRows.add(ordinary("S" + i, "x"));
        assertOversized(input(tooManyRows, "en"));

        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 2049; i++) huge.append('x');
        assertOversized(input(Collections.singletonList(ordinary("S0", huge.toString())), "en"));
    }

    @Test
    public void aModelTooSmallForTheChunkIsRefusedRatherThanCalled() {
        AiChunkPlanner.Input input =
                input(Collections.singletonList(ordinary("S0", "long enough source")), "en");
        input.model = new AiModelLimits(2, 2);
        assertOversized(input);
    }

    private static void assertOversized(AiChunkPlanner.Input input) {
        try {
            AiChunkPlanner.plan(input);
            fail("expected the plan to be refused as oversized");
        } catch (AiOversizedException expected) {
            assertTrue(expected.getMessage().startsWith("oversized:"));
        }
    }

    // --- baselines and revisions --------------------------------------------

    @Test
    public void layeredSoundSendsTheLocalBaselineAndAiOnlyDoesNot() {
        AiLine row = AiLine.withBaseline("S0", "\u0e09\u0e31\u0e19 love", null, true,
                "\u0e09\u0e31\u0e19 love", "deterministic");
        AiChunkPlanner.Input layered = input(Collections.singletonList(row), "Latin");
        layered.layer = LayerKind.SOUND;
        layered.model = new AiModelLimits(32_768, 2_048);
        assertEquals("\u0e09\u0e31\u0e19 love",
                AiChunkPlanner.plan(layered).chunks.get(0).items.get(0).previous);

        AiChunkPlanner.Input aiOnly = input(Collections.singletonList(row), "Latin");
        aiOnly.layer = LayerKind.SOUND;
        aiOnly.model = new AiModelLimits(32_768, 2_048);
        aiOnly.useSoundBaseline = false;
        assertNull(AiChunkPlanner.plan(aiOnly).chunks.get(0).items.get(0).previous);
    }

    @Test
    public void meaningRefinementSendsGoogleAsPreviousAndUsesItsPrompt() {
        AiLine row = AiLine.withBaseline("M0", "Hola", null, false, "Hello", "google");
        AiChunkPlanner.Input input = input(Collections.singletonList(row), "en");
        input.layer = LayerKind.MEANING;
        input.useMeaningBaseline = true;
        input.baselineRefinement = true;

        AiPlannedChunk chunk = AiChunkPlanner.plan(input).chunks.get(0);

        assertEquals("Hello", chunk.items.get(0).previous);
        assertTrue(AiContract.buildSystemPrompt(LayerKind.MEANING, "en", false, false, true)
                .contains("Google Translate draft"));
    }

    @Test
    public void arevisionCarriesTheLatestAcceptedOutputForEveryRowOrIsRefused() {
        AiLine row = ordinary("S0", "\u611b");
        AiChunkPlanner.Input revision = input(Collections.singletonList(row), "en");
        revision.instructions = "Make it warmer.";
        Map<String, String> previous = new LinkedHashMap<>();
        previous.put("S0", "first AI");
        revision.previousById = previous;

        AiPlannedChunk chunk = AiChunkPlanner.plan(revision).chunks.get(0);
        assertEquals("first AI", chunk.items.get(0).previous);
        assertTrue(chunk.requestJson.contains("\"instructions\":\"Make it warmer.\""));
        assertTrue(chunk.requestJson.contains("\"p\":\"first AI\""));

        AiChunkPlanner.Input incomplete = input(Collections.singletonList(row), "en");
        incomplete.previousById = new LinkedHashMap<>();
        try {
            AiChunkPlanner.plan(incomplete);
            fail("expected a revision missing a row's accepted output to be refused");
        } catch (AiProtocolException expected) {
            assertEquals("previous_output_missing:S0", expected.getMessage());
        }
    }

    @Test
    public void anOversizedBaselineIsRefusedLikeAnOversizedSource() {
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 4097; i++) huge.append('x');
        AiLine row = AiLine.withBaseline("S0", "hola", null, true, huge.toString(),
                "deterministic");
        AiChunkPlanner.Input input = input(Collections.singletonList(row), "Latin");
        input.layer = LayerKind.SOUND;
        assertOversized(input);
    }

    // --- helpers ------------------------------------------------------------

    private static List<String> ids(AiChunkPlan plan) {
        List<String> out = new ArrayList<>();
        for (AiPlannedChunk chunk : plan.chunks) out.add(chunk.id);
        return out;
    }

    private static List<Integer> sizes(AiChunkPlan plan) {
        List<Integer> out = new ArrayList<>();
        for (AiPlannedChunk chunk : plan.chunks) out.add(chunk.items.size());
        return out;
    }
}
