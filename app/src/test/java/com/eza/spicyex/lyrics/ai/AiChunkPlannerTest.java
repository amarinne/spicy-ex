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
    public void estimatedOutputIsHalfTheSourceBytesRoundedUpPlusTheReasoningAllowance() {
        AiPlannedChunk chunk = AiChunkPlanner.plan(
                input(Collections.singletonList(ordinary("S0", "hello")), "en")).chunks.get(0);
        assertEquals(5, chunk.sourceUtf8Bytes);
        assertEquals(AiChunkPlanner.ceilHalf(5)
                        + AiContract.RESPONSE_ITEM_OVERHEAD_TOKENS
                        + AiContract.REASONING_OUTPUT_ALLOWANCE_TOKENS,
                chunk.estimatedOutputTokens);
    }

    @Test
    public void theVisibleEstimateAndTheAllowanceStaySeparateTerms() {
        assertEquals("the visible-output half is unchanged by the headroom work",
                3, AiChunkPlanner.ceilHalf(5));
        assertEquals("a plain limits object gets the contract default",
                AiContract.REASONING_OUTPUT_ALLOWANCE_TOKENS,
                AiChunkPlanner.reasoningAllowance(MODEL));
        assertEquals("an unmeasured descriptor also gets the contract default",
                AiContract.REASONING_OUTPUT_ALLOWANCE_TOKENS,
                AiChunkPlanner.reasoningAllowance(new AiModelDescriptor("m", "1", 1, 1, null)));
    }

    @Test
    public void aMeasuredReasoningModelReplacesTheDefaultAllowanceAndAShallowOneShrinksIt() {
        AiModelDescriptor reasoner = new AiModelDescriptor("reasoner", "1", 32_768, 8_192,
                Collections.singletonList("chat.completions"), 900);
        assertEquals(900, AiChunkPlanner.reasoningAllowance(reasoner));

        AiModelDescriptor shallow = new AiModelDescriptor("shallow", "1", 32_768, 8_192,
                Collections.singletonList("chat.completions"), 24);
        assertEquals(24, AiChunkPlanner.reasoningAllowance(shallow));
        AiChunkPlanner.Input input = input(
                Collections.singletonList(ordinary("S0", "hello")), "en");
        input.model = shallow;
        assertEquals(AiChunkPlanner.ceilHalf(5)
                        + AiContract.RESPONSE_ITEM_OVERHEAD_TOKENS + 24,
                AiChunkPlanner.plan(input).chunks.get(0).estimatedOutputTokens);
    }

    /**
     * The reasoning allowance is bought once for the call; the item envelope is paid per row.
     *
     * <p>Both are separate from the source-text half, and conflating them is how the estimate came
     * to under-count a lyric document: an id with an eight-hex digest and the surrounding JSON cost
     * about the same for every row, and lyric lines are short and numerous enough that the envelope
     * outweighs the text.
     */
    @Test
    public void theHeadroomIsPerCallWhileTheItemEnvelopeIsPerRow() {
        List<AiLine> rows = new ArrayList<>();
        for (int i = 0; i < 8; i++) rows.add(ordinary("S" + i, "line " + i));
        int sourceBytes = 0;
        for (AiLine row : rows) sourceBytes += AiText.utf8Bytes(row.sourceText);

        AiPlannedChunk chunk = AiChunkPlanner.plan(input(rows, "en")).chunks.get(0);

        assertEquals(sourceBytes, chunk.sourceUtf8Bytes);
        assertEquals(AiChunkPlanner.ceilHalf(sourceBytes)
                        + 8 * AiContract.RESPONSE_ITEM_OVERHEAD_TOKENS
                        + AiContract.REASONING_OUTPUT_ALLOWANCE_TOKENS,
                chunk.estimatedOutputTokens);
    }

    /** For short lyric lines the envelope is the larger half, which is why omitting it truncated. */
    @Test
    public void theEnvelopeOutweighsTheTextForShortLyricLines() {
        List<AiLine> rows = new ArrayList<>();
        for (int i = 0; i < 40; i++) rows.add(ordinary("S" + i, "Bah ouais"));
        int sourceBytes = 40 * AiText.utf8Bytes("Bah ouais");

        assertTrue("envelope " + (40 * AiContract.RESPONSE_ITEM_OVERHEAD_TOKENS)
                        + " must exceed text estimate " + AiChunkPlanner.ceilHalf(sourceBytes),
                40 * AiContract.RESPONSE_ITEM_OVERHEAD_TOKENS
                        > AiChunkPlanner.ceilHalf(sourceBytes));
    }

    @Test
    public void aTruncatedChunkReplansDeterministicallyUnderADerivedTighterEstimate() {
        List<AiLine> rows = new ArrayList<>();
        for (int i = 0; i < 8; i++) rows.add(ordinary("S" + i, "line " + i));
        AiChunkPlanner.Input input = input(rows, "en");
        AiPlannedChunk failed = AiChunkPlanner.plan(input).chunks.get(0);

        List<AiPlannedChunk> first = AiChunkPlanner.replan(input, failed);
        List<AiPlannedChunk> second = AiChunkPlanner.replan(input, failed);

        assertEquals(Arrays.asList("C0.0", "C0.1"), chunkIds(first));
        assertEquals(Arrays.asList(4, 4), chunkSizes(first));
        assertEquals(chunkIds(first), chunkIds(second));
        assertEquals(chunkSizes(first), chunkSizes(second));
        List<String> replannedIds = new ArrayList<>();
        for (AiPlannedChunk child : first) {
            replannedIds.addAll(child.itemIds());
            assertTrue(child.estimatedOutputTokens < failed.estimatedOutputTokens);
        }
        assertEquals(failed.itemIds(), replannedIds);
    }

    @Test
    public void aSingleItemTruncationCannotBeReplanned() {
        AiChunkPlanner.Input input = input(
                Collections.singletonList(ordinary("S0", "one indivisible row")), "en");
        AiPlannedChunk failed = AiChunkPlanner.plan(input).chunks.get(0);

        assertTrue(AiChunkPlanner.replan(input, failed).isEmpty());
    }

    private static List<String> chunkIds(List<AiPlannedChunk> chunks) {
        List<String> out = new ArrayList<>();
        for (AiPlannedChunk chunk : chunks) out.add(chunk.id);
        return out;
    }

    private static List<Integer> chunkSizes(List<AiPlannedChunk> chunks) {
        List<Integer> out = new ArrayList<>();
        for (AiPlannedChunk chunk : chunks) out.add(chunk.items.size());
        return out;
    }

    @Test
    public void theSingleCallByteBudgetShrinksByTwiceTheAllowance() {
        // Five 2,000-byte rows sit just inside the shrunken bound:
        // ceilHalf(10000)+1024 <= 6144 keeps one call.
        assertEquals(1, AiChunkPlanner.plan(input(rowsOfBytes(5, 2_000), "en")).chunks.size());

        // Six such rows cross it and fall through to deterministic chunking.
        assertTrue(AiChunkPlanner.plan(input(rowsOfBytes(6, 2_000), "en")).chunks.size() > 1);
    }

    private static List<AiLine> rowsOfBytes(int count, int bytesPerRow) {
        List<AiLine> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            StringBuilder text = new StringBuilder();
            while (AiText.utf8Bytes(text.toString()) < bytesPerRow) text.append('a');
            rows.add(ordinary("S" + i, text.toString()));
        }
        return rows;
    }

    @Test
    public void aMultiSegmentRowIsSentAsOneItemPerSegmentWithDerivedIds() {
        AiLine row = ordinary("r0#ab12", "\u6e90\u3044 / \u6e90\u306b / \u6e90\u3055");
        List<AiRequestItem> items =
                AiChunkPlanner.plan(input(Collections.singletonList(row), "en"))
                        .chunks.get(0).items;

        assertEquals(3, items.size());
        assertEquals("r0#ab12~0", items.get(0).id);
        assertEquals("\u6e90\u3044", items.get(0).source);
        assertEquals("r0#ab12~1", items.get(1).id);
        assertEquals("\u6e90\u306b", items.get(1).source);
        assertEquals("r0#ab12~2", items.get(2).id);
        assertFalse(items.get(2).source.contains(" / "));
        assertEquals("the voice hint travels with every segment",
                row.voice, items.get(1).voice);
    }

    /**
     * The source decides the segmentation; the baseline never overrides it.
     *
     * <p>This replaces an assertion that a disagreeing baseline kept the joined row. That was the
     * one path still sending a multi-segment row whole, so the validator still required a delimiter
     * count that the prompt no longer asks for — and Google output disagrees often enough that the
     * Google-draft and layered pipelines lived on that path.
     */
    @Test
    public void theSourceDecidesSegmentationAndTheBaselineNeverOverridesIt() {
        AiLine clean = AiLine.withBaseline("r0#ab12", "a / b", null, true, "x / y", "google");
        AiChunkPlanner.Input cleanInput = input(Collections.singletonList(clean), "en");
        cleanInput.useMeaningBaseline = true;
        List<AiRequestItem> cleanItems =
                AiChunkPlanner.plan(cleanInput).chunks.get(0).items;
        assertEquals(2, cleanItems.size());
        assertEquals("x", cleanItems.get(0).previous);
        assertEquals("y", cleanItems.get(1).previous);

        AiLine mismatched = AiLine.withBaseline("r0#ab12", "a / b", null, true,
                "whole draft", "google");
        AiChunkPlanner.Input mismatchedInput = input(Collections.singletonList(mismatched), "en");
        mismatchedInput.useMeaningBaseline = true;
        List<AiRequestItem> split = AiChunkPlanner.plan(mismatchedInput).chunks.get(0).items;
        assertEquals(2, split.size());
        assertEquals("a", split.get(0).source);
        assertEquals("b", split.get(1).source);
        for (AiRequestItem item : split) {
            assertNull("a disagreeing baseline must not travel", item.previous);
        }
    }

    @Test
    public void segmentIdsNeverCollideWithRowIdsAndRoundTrip() {
        assertEquals(-1, AiContract.segmentIndexOf("r0#ab12"));
        assertEquals(2, AiContract.segmentIndexOf(AiContract.segmentId("r0#ab12", 2)));
        assertEquals("r0#ab12", AiContract.rowIdOf(AiContract.segmentId("r0#ab12", 2)));
        assertEquals("r0#ab12", AiContract.rowIdOf("r0#ab12"));
        assertEquals(-1, AiContract.segmentIndexOf("r0#notasegment~x"));
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

    /**
     * A baseline that segments differently from the source must not suppress the split.
     *
     * <p>Google output routinely merges or drops {@code " / "} boundaries. While the split was
     * conditional on the baseline agreeing, those rows went out joined — and the validator still
     * required a delimiter count the prompt had stopped asking for, so the Google-draft and layered
     * pipelines carried an unstated rule no model could comply with.
     */
    @Test
    public void aDisagreeingBaselineIsDroppedRatherThanSuppressingTheSplit() {
        AiLine row = AiLine.withBaseline("M0", "one / two / three", null, false,
                "google merged them all", "google");
        AiChunkPlanner.Input input = input(Collections.singletonList(row), "en");
        input.layer = LayerKind.MEANING;
        input.useMeaningBaseline = true;
        input.baselineRefinement = true;

        List<AiRequestItem> items = AiChunkPlanner.plan(input).chunks.get(0).items;

        assertEquals(3, items.size());
        for (AiRequestItem item : items) {
            assertTrue(item.id, item.id.startsWith("M0"));
            assertEquals(1, AiText.segmentCount(item.source));
            assertNull("a disagreeing baseline must not travel", item.previous);
        }
    }

    /** A baseline that segments the same way still travels, one segment per item. */
    @Test
    public void anAgreeingBaselineIsSplitAlongsideTheSource() {
        AiLine row = AiLine.withBaseline("M1", "one / two", null, false, "uno / dos", "google");
        AiChunkPlanner.Input input = input(Collections.singletonList(row), "en");
        input.layer = LayerKind.MEANING;
        input.useMeaningBaseline = true;
        input.baselineRefinement = true;

        List<AiRequestItem> items = AiChunkPlanner.plan(input).chunks.get(0).items;

        assertEquals(2, items.size());
        assertEquals("uno", items.get(0).previous);
        assertEquals("dos", items.get(1).previous);
    }
}
