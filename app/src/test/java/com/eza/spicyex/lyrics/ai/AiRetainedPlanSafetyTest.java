package com.eza.spicyex.lyrics.ai;

import com.eza.spicyex.lyrics.session.LayerKind;
import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import static org.junit.Assert.*;

/** Retained work must be checked as a whole before spending or changing paid evidence. */
public class AiRetainedPlanSafetyTest {
    private static AiLayerRunner.Args args(int count, int allowance, FakeAiRecordStore store) {
        AiModelDescriptor model = new AiModelDescriptor("fake-model", "1", 32768, 8192,
                Collections.singletonList("generateContent"), allowance);
        AiProviderConfig provider = new AiProviderConfig(LayerKind.MEANING, null, "1", model,
                "en", AiContract.PROMPT_VERSION, false);
        AiLayerRunner.Args args = new AiLayerRunner.Args();
        args.explicitRetry = true; // Recovery in these fixtures represents an explicit owner retry.
        args.config = new AiRunConfig(LayerKind.MEANING, "doc", "config", "fake",
                AiLyricContext.EMPTY, provider, "", null, null);
        args.rows = new ArrayList<>();
        for (int i = 0; i < count; i++) args.rows.add(AiLine.of("r" + i,
                "hello world line " + i, null, false));
        args.provider = new FakeAiProvider();
        args.store = store;
        args.useBaseline = false;
        return args;
    }

    private static AiChunkPlan plan(AiLayerRunner.Args args) {
        AiChunkPlanner.Input input = new AiChunkPlanner.Input();
        input.rows = args.rows;
        input.layer = args.config.layer;
        input.target = args.config.targetLang;
        input.model = args.config.provider.model;
        return AiChunkPlanner.plan(input);
    }

    private static AiPaidRecord stripped(AiPaidRecord record) {
        AiPaidRecord out = AiPaidRecordCodec.decode(AiPaidRecordCodec.encode(record, false));
        assertNotNull(out);
        return out;
    }

    private static void refusesWithoutMutation(AiLayerRunner.Args args, FakeAiRecordStore store) {
        String before = AiPaidRecordCodec.encode(store.peek(args.config));
        int commits = store.commits, reservations = store.reservations;
        for (boolean allow : new boolean[]{false, true, true}) {
            args.allowProviderRequest = allow;
            assertFalse(AiLayerRunner.run(args).hasOutput());
            assertTrue(((FakeAiProvider) args.provider).calls.isEmpty());
            assertEquals(commits, store.commits);
            assertEquals(reservations, store.reservations);
            assertEquals(before, AiPaidRecordCodec.encode(store.peek(args.config)));
        }
    }

    @Test public void probeDriftCannotDispatchDifferentIdsIntoAnExistingPaidChunk() {
        FakeAiRecordStore store = new FakeAiRecordStore();
        AiLayerRunner.Args first = args(100, -1, store);
        assertEquals(1, plan(first).chunks.size());
        first.provider = new FakeAiProvider((request, config, signal) ->
                AiProviderResult.failed(AiProviderFailure.auth()));
        assertEquals(AiRunOutcome.Kind.FAILED, AiLayerRunner.run(first).kind);
        AiLayerRunner.Args changed = args(100, 4000, store);
        assertEquals(2, plan(changed).chunks.size());
        String before = AiPaidRecordCodec.encode(store.peek(first.config));
        int commits = store.commits, reservations = store.reservations;
        changed.allowProviderRequest = false;
        assertFalse(AiLayerRunner.run(changed).hasOutput());
        assertEquals(commits, store.commits);
        assertEquals(reservations, store.reservations);
        assertEquals(before, AiPaidRecordCodec.encode(store.peek(first.config)));
        changed.allowProviderRequest = true;
        AiRunOutcome recovery = AiLayerRunner.run(changed);
        assertEquals(AiRunOutcome.Kind.COMPLETED, recovery.kind);
        assertEquals(100, recovery.record.items().size());
        assertEquals(1, ((FakeAiProvider) changed.provider).calls.size());
        assertEquals(100, ((FakeAiProvider) changed.provider).calls.get(0).request.items.size());
        assertEquals(100, recovery.record.chunk("C0").ids.size());
        assertEquals(1, recovery.record.chunks().size());
        assertEquals(AiRunOutcome.Kind.REUSED, AiLayerRunner.run(args(100, -1, store)).kind);
    }

    @Test public void corruptLaterCompleteChunkStopsBeforeEarlierMissingChunkIsBought() {
        FakeAiRecordStore store = new FakeAiRecordStore();
        AiLayerRunner.Args args = args(129, -1, store);
        AiChunkPlan plan = plan(args);
        assertEquals(3, plan.chunks.size());
        AiPaidRecord record = AiPaidRecord.begin(args.config, 1);
        AiChunkRecord later = AiChunkRecord.forChunk(plan.chunks.get(1));
        later.status = AiChunkRecord.Status.COMPLETE;
        record.putChunk("C1", later);
        record.putChunk("C2", AiChunkRecord.forChunk(plan.chunks.get(2)));
        store.commit(args.config, stripped(record));
        refusesWithoutMutation(args, store);
    }

    @Test public void failedRetainedRootMustHaveExactlyTheCurrentRootIdsBeforeReopen() {
        FakeAiRecordStore store = new FakeAiRecordStore();
        AiLayerRunner.Args args = args(3, -1, store);
        AiPaidRecord record = AiPaidRecord.begin(args.config, 1);
        AiChunkRecord bad = new AiChunkRecord(Arrays.asList("r0", "r1"), "");
        bad.status = AiChunkRecord.Status.FAILED;
        bad.failure = AiChunkFailure.of(AiFailureReason.AUTH_REJECTED);
        record.status = AiPaidRecord.Status.FAILED;
        record.putChunk("C0", bad);
        store.commit(args.config, stripped(record));
        refusesWithoutMutation(args, store);
    }

    private static AiPaidRecord fallback(AiLayerRunner.Args args) {
        AiPaidRecord record = AiPaidRecord.begin(args.config, 1);
        AiChunkRecord root = AiChunkRecord.forChunk(plan(args).chunks.get(0));
        root.status = AiChunkRecord.Status.COMPLETED_FALLBACK;
        root.fallbackRowIds.add("r1");
        record.putChunk("C0", root);
        record.putItem("r0", "zero");
        record.putItem("r2", "two");
        return record;
    }

    @Test public void terminalProofRequiresEverySuccessfulRowAndNoExtraOutput() {
        AiLayerRunner.Args args = args(3, -1, new FakeAiRecordStore());
        AiPaidRecord missing = AiPaidRecord.begin(args.config, 1);
        missing.putChunk("C0", fallback(args).chunk("C0"));
        missing.putItem("r0", "zero");
        assertFalse(stripped(missing).hasTerminalFallback(plan(args)));
        AiPaidRecord extra = fallback(args);
        extra.putItem("foreign", "unexpected");
        assertFalse(stripped(extra).hasTerminalFallback(plan(args)));
    }

    @Test public void terminalProofRequiresCurrentRootIdsNotJustItsOutputRows() {
        AiLayerRunner.Args args = args(3, -1, new FakeAiRecordStore());
        AiPaidRecord record = fallback(args);
        AiChunkRecord wrong = new AiChunkRecord(Arrays.asList("r2", "r1", "r0"), "");
        wrong.status = AiChunkRecord.Status.COMPLETED_FALLBACK;
        wrong.fallbackRowIds.add("r1");
        record.putChunk("C0", wrong);
        assertFalse(stripped(record).hasTerminalFallback(plan(args)));
    }

    @Test public void terminalProofRequiresTerminalDescendantStatus() {
        AiLayerRunner.Args args = args(3, -1, new FakeAiRecordStore());
        AiPaidRecord record = fallback(args);
        AiChunkRecord child = new AiChunkRecord(Collections.singletonList("r0"), "");
        child.status = AiChunkRecord.Status.FAILED;
        record.putChunk("C0.0", child);
        AiChunkRecord rest = new AiChunkRecord(Arrays.asList("r1", "r2"), "");
        rest.status = AiChunkRecord.Status.COMPLETED_FALLBACK;
        rest.fallbackRowIds.add("r1");
        record.putChunk("C0.1", rest);
        assertFalse(stripped(record).hasTerminalFallback(plan(args)));
    }

    @Test public void orphanDescendantCannotBeIgnoredWhenSkippingAnOtherwiseProvenRoot() {
        AiLayerRunner.Args args = args(3, -1, new FakeAiRecordStore());
        AiPaidRecord record = fallback(args);
        AiChunkRecord orphan = new AiChunkRecord(Collections.singletonList("r0"), "");
        orphan.status = AiChunkRecord.Status.COMPLETE;
        record.putChunk("C0.9.0", orphan);
        assertFalse(stripped(record).hasProvenTerminalChunk(plan(args).chunks.get(0)));
    }

    @Test public void terminalProofRequiresTheRecordedPlanVersion() {
        AiLayerRunner.Args args = args(3, -1, new FakeAiRecordStore());
        AiPaidRecord held = fallback(args);
        AiPaidRecord old = new AiPaidRecord(held.layer, held.docDigest, held.configId,
                held.providerId, held.modelName, held.targetLang, held.sourceLanguage,
                held.pronunciationSystem, held.schemaVersion, held.chunkPlanVersion - 1);
        for (java.util.Map.Entry<String, String> item : held.items().entrySet()) {
            old.putItem(item.getKey(), item.getValue());
        }
        old.putChunk("C0", held.chunk("C0"));
        assertFalse(stripped(old).hasTerminalFallback(plan(args)));
    }

    @Test public void snapshotFreeFullyRecordedFailedRootRecoversItsOriginalIds() {
        FakeAiRecordStore store = new FakeAiRecordStore();
        AiLayerRunner.Args original = args(100, -1, store);
        AiPaidRecord record = AiPaidRecord.begin(original.config, 1);
        AiChunkRecord failed = AiChunkRecord.forChunk(plan(original).chunks.get(0));
        failed.status = AiChunkRecord.Status.FAILED;
        failed.failure = AiChunkFailure.of(AiFailureReason.AUTH_REJECTED);
        failed.inputTokens = 9;
        record.inputTokens = 9;
        record.status = AiPaidRecord.Status.FAILED;
        record.putChunk("C0", failed);
        store.commit(original.config, stripped(record));
        AiLayerRunner.Args changed = args(100, 4000, store);
        AiRunOutcome outcome = AiLayerRunner.run(changed);
        assertEquals(AiRunOutcome.Kind.COMPLETED, outcome.kind);
        assertEquals(100, outcome.record.items().size());
        assertEquals(failed.ids, outcome.record.chunk("C0").ids);
        assertEquals(1, ((FakeAiProvider) changed.provider).calls.size());
        assertEquals(100, ((FakeAiProvider) changed.provider).calls.get(0).request.items.size());
        assertTrue(outcome.record.inputTokens > 9);
        assertNull("Do not infer the original planning snapshot", outcome.record.planning);
    }

    @Test public void snapshotFreeSplitRootsKeepPaidOutputWhenCurrentPlanBecomesSingle() {
        FakeAiRecordStore store = new FakeAiRecordStore();
        AiLayerRunner.Args original = args(100, 4000, store);
        original.provider = new FakeAiProvider((request, config, signal) ->
                AiProviderResult.ok(FakeAiProvider.echo(request), AiUsage.of(10, 8), AiFinishReason.STOP, 1),
                (request, config, signal) -> AiProviderResult.failed(AiProviderFailure.auth()));
        AiPaidRecord held = AiLayerRunner.run(original).record;
        held.planning = null; // A purchase from before the optional snapshot existed.
        held = stripped(held);
        assertEquals(64, held.items().size());
        List<String> paidIds = held.chunk("C0").ids;
        int paidInput = held.chunk("C0").inputTokens;
        store.commit(original.config, held);
        AiLayerRunner.Args changed = args(100, -1, store);
        assertEquals(1, plan(changed).chunks.size());
        AiRunOutcome outcome = AiLayerRunner.run(changed);
        assertEquals(AiRunOutcome.Kind.COMPLETED, outcome.kind);
        assertEquals(100, outcome.record.items().size());
        assertEquals(paidIds, outcome.record.chunk("C0").ids);
        assertEquals(paidInput, outcome.record.chunk("C0").inputTokens);
        assertEquals(1, ((FakeAiProvider) changed.provider).calls.size());
        assertEquals(36, ((FakeAiProvider) changed.provider).calls.get(0).request.items.size());
        assertNull(outcome.record.planning);
    }

    @Test public void snapshotFreeMissingRootBoundariesAreNotInferredFromChangedPlanning() {
        FakeAiRecordStore store = new FakeAiRecordStore();
        AiLayerRunner.Args original = args(100, 4000, store);
        AiPaidRecord held = AiPaidRecord.begin(original.config, 1);
        AiChunkRecord paid = AiChunkRecord.forChunk(plan(original).chunks.get(0));
        paid.status = AiChunkRecord.Status.COMPLETE;
        held.putChunk("C0", paid);
        for (String id : paid.ids) held.putItem(id, "bought");
        store.commit(original.config, stripped(held));
        refusesWithoutMutation(args(100, -1, store), store);
    }

    @Test public void pinnedPlanningCannotWaiveCurrentInputOrOutputLimits() {
        for (boolean input : new boolean[]{false, true}) {
            FakeAiRecordStore store = new FakeAiRecordStore();
            AiLayerRunner.Args original = args(100, -1, store);
            original.provider = new FakeAiProvider((request, config, signal) ->
                    AiProviderResult.failed(AiProviderFailure.auth()));
            AiLayerRunner.run(original);
            AiLayerRunner.Args restricted = args(100, -1, store);
            AiModelDescriptor limits = new AiModelDescriptor("fake-model", "1", input ? 100 : 32768,
                    input ? 8192 : 1000, Collections.emptyList());
            AiProviderConfig provider = new AiProviderConfig(LayerKind.MEANING, null, "1", limits,
                    "en", AiContract.PROMPT_VERSION, false);
            restricted.config = new AiRunConfig(LayerKind.MEANING, "doc", "config", "fake",
                    AiLyricContext.EMPTY, provider, "", null, null);
            refusesWithoutMutation(restricted, store);
            AiRunOutcome recovery = AiLayerRunner.run(args(100, -1, store));
            assertEquals(AiRunOutcome.Kind.COMPLETED, recovery.kind);
            assertEquals(100, recovery.record.items().size());
        }
    }

    @Test public void planningSnapshotSurvivesStrippingAndRejectsMalformedValuesReadOnly() {
        FakeAiRecordStore store = new FakeAiRecordStore();
        AiLayerRunner.Args args = args(100, 4000, store);
        args.provider = new FakeAiProvider((request, config, signal) ->
                AiProviderResult.failed(AiProviderFailure.auth()));
        AiPaidRecord held = stripped(AiLayerRunner.run(args).record);
        assertEquals(32768, held.planning.inputTokenLimit);
        assertEquals(8192, held.planning.outputTokenLimit);
        assertEquals(4000, held.planning.reasoningAllowanceTokens);
        assertEquals(held.planning.encode(), stripped(held).planning.encode());
        for (String malformed : Arrays.asList("null", "[]", "{}",
                "{\"inputTokenLimit\":0,\"outputTokenLimit\":8192,\"reasoningAllowanceTokens\":1}",
                "{\"inputTokenLimit\":32768,\"outputTokenLimit\":-1,\"reasoningAllowanceTokens\":1}",
                "{\"inputTokenLimit\":32768,\"outputTokenLimit\":8192,\"reasoningAllowanceTokens\":8193}",
                "{\"inputTokenLimit\":2147483648,\"outputTokenLimit\":8192,\"reasoningAllowanceTokens\":1}",
                "{\"inputTokenLimit\":1.5,\"outputTokenLimit\":8192,\"reasoningAllowanceTokens\":1}",
                "{\"inputTokenLimit\":\"32768\",\"outputTokenLimit\":8192,\"reasoningAllowanceTokens\":1}",
                "{\"inputTokenLimit\":32768,\"outputTokenLimit\":8192,\"reasoningAllowanceTokens\":1,\"extra\":0}")) {
            JsonObject payload = JsonParser.parseString(AiPaidRecordCodec.encode(held, false)).getAsJsonObject();
            payload.add("planning", JsonParser.parseString(malformed));
            AiPaidRecord invalid = AiPaidRecordCodec.decode(payload.toString());
            assertNotNull("Corruption must not make a retained purchase absent", invalid);
            assertNotNull("Corruption must not become a missing optional snapshot", invalid.planning);
            assertFalse(invalid.planning.isValid());
            FakeAiRecordStore corruptStore = new FakeAiRecordStore();
            corruptStore.commit(args.config, invalid);
            refusesWithoutMutation(args(100, 4000, corruptStore), corruptStore);
        }
    }

    @Test public void interruptedHierarchyKeepsItsSnapshotAndNeverRepurchasesPaidParentOrChild() {
        FakeAiRecordStore store = new FakeAiRecordStore();
        AiLayerRunner.Args original = args(8, -1, store);
        AiSignal signal = new AiSignal();
        original.signal = signal;
        original.provider = new FakeAiProvider(FakeAiProvider.body("", AiUsage.of(7, 3), AiFinishReason.LENGTH),
                (request, config, callSignal) -> {
                    AiProviderResult result = AiProviderResult.ok(FakeAiProvider.echo(request),
                            AiUsage.of(9, 5), AiFinishReason.STOP, 1);
                    signal.abort("track_change");
                    return result;
                });
        AiRunOutcome cancelled = AiLayerRunner.run(original);
        assertEquals(AiRunOutcome.Kind.CANCELLED, cancelled.kind);
        AiPaidRecord held = stripped(store.peek(original.config));
        assertEquals(AiContract.REASONING_OUTPUT_ALLOWANCE_TOKENS, held.planning.reasoningAllowanceTokens);
        assertEquals(AiChunkRecord.Status.REPLANNED, held.chunk("C0").status);
        assertEquals(AiChunkRecord.Status.COMPLETE, held.chunk("C0.0").status);
        int parentInput = held.chunk("C0").inputTokens;
        int childInput = held.chunk("C0.0").inputTokens;
        List<String> childIds = held.chunk("C0.1") == null ? Arrays.asList("r4", "r5", "r6", "r7")
                : held.chunk("C0.1").ids;
        AiLayerRunner.Args changed = args(8, 4000, store);
        AiRunOutcome resumed = AiLayerRunner.run(changed);
        assertEquals(AiRunOutcome.Kind.COMPLETED, resumed.kind);
        assertEquals(8, resumed.record.items().size());
        assertEquals(parentInput, resumed.record.chunk("C0").inputTokens);
        assertEquals(childInput, resumed.record.chunk("C0.0").inputTokens);
        assertEquals(1, ((FakeAiProvider) changed.provider).calls.size());
        assertEquals(childIds, resumed.record.chunk("C0.1").ids);
        assertEquals(AiContract.REASONING_OUTPUT_ALLOWANCE_TOKENS,
                resumed.record.planning.reasoningAllowanceTokens);
    }

    @Test public void recordedRootRecoveryRejectsSplitCanonicalRowsAndHierarchicalEvidence() {
        FakeAiRecordStore store = new FakeAiRecordStore();
        AiLayerRunner.Args args = args(2, -1, store);
        args.rows = Arrays.asList(AiLine.of("r0", "one / two", null, false),
                AiLine.of("r1", "three", null, false));
        AiPaidRecord record = AiPaidRecord.begin(args.config, 1);
        record.putChunk("C0", new AiChunkRecord(Collections.singletonList("r0~0"), ""));
        record.putChunk("C1", new AiChunkRecord(Arrays.asList("r0~1", "r1"), ""));
        store.commit(args.config, stripped(record));
        refusesWithoutMutation(args, store);
        AiPaidRecord nested = AiPaidRecord.begin(args.config, 1);
        nested.putChunk("C0", new AiChunkRecord(Arrays.asList("r0~0", "r0~1", "r1"), ""));
        nested.putChunk("C0.0", new AiChunkRecord(Collections.singletonList("r0~0"), ""));
        store.commit(args.config, stripped(nested));
        refusesWithoutMutation(args, store);
    }

    @Test public void snapshotFreeRecoveryRejectsAnImpossibleRecordedRootSize() {
        FakeAiRecordStore store = new FakeAiRecordStore();
        AiLayerRunner.Args args = args(129, -1, store);
        List<String> ids = new ArrayList<>();
        for (AiLine row : args.rows) ids.add(row.id);
        AiPaidRecord record = AiPaidRecord.begin(args.config, 1);
        record.putChunk("C0", new AiChunkRecord(ids, ""));
        store.commit(args.config, stripped(record));
        refusesWithoutMutation(args, store);
    }
}
